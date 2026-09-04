package com.trackoff.io.lastfm;

import com.trackoff.model.Song;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The "Update Last.fm plays" job: re-fetches a playlist's Last.fm data
 * off the FX thread, persists it, and reports back when it's done.
 *
 * It runs in two phases:
 *   1. {@link Phase#SONGS} — a play count per track, into {@link PlaycountStore}.
 *   2. {@link Phase#ARTISTS} — the account's whole-library play count for
 *      each distinct primary artist, into {@link ArtistPlaycountStore}.
 * Both live in one job behind one button because they're one question
 * ("refresh my Last.fm numbers for this playlist") and because the
 * Artists view is unusable with only half of them.
 *
 * Design notes:
 *   - Jobs are registered per playlist in {@link #RUNNING}, so the user
 *     can leave the Last.fm Manager mid-update and come back to a
 *     still-running job (the view re-attaches via {@link #jobFor}
 *     instead of starting a second one). A second start() for a
 *     playlist already updating returns the existing job untouched.
 *   - Fetching is parallel ({@link #FETCH_THREADS}) but writing is not:
 *     workers hand results to the coordinator, which flushes them in
 *     batches. {@code Database} hands out one shared JDBC Connection, so
 *     concurrent writes from the fetch pool would be asking for trouble;
 *     batching also turns thousands of individual UPDATEs into a handful
 *     of transactions.
 *   - Throughput is bounded by {@link LastFmClient}'s global rate
 *     limiter (~3.3 req/sec across all threads), so a big playlist takes
 *     minutes. That's exactly why this is a deliberate background action
 *     instead of something that runs on every screen open.
 */
public final class PlaycountUpdater {

    private static final int FETCH_THREADS = 4;

    /** Persist every this many resolved items, so a mid-run cancel still banks progress. */
    private static final int FLUSH_EVERY = 25;

    private static final Map<String, Job> RUNNING = new ConcurrentHashMap<>();

    private PlaycountUpdater() {}

    public enum Phase {
        SONGS("track plays"),
        ARTISTS("artist plays");

        private final String label;
        Phase(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Everything a finished (or cancelled) run produced. */
    public record Result(
            Map<String, Long> songPlays,      // song id  -> play count
            Map<String, Long> artistPlays,    // artist key -> play count
            int failedSongs,
            int failedArtists,
            boolean cancelled
    ) {
        public int failed() { return failedSongs + failedArtists; }
        public int updated() { return songPlays.size() + artistPlays.size(); }
    }

    /** Progress + completion callbacks. Always delivered on the FX thread. */
    public interface Listener {
        void onProgress(Phase phase, int done, int total);
        void onFinished(Result result);
    }

    /** A running update. Safe to hold on to; safe to re-attach a new listener to. */
    public static final class Job {
        private final String playlistId;
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Phase phase = Phase.SONGS;
        private volatile int total;
        private volatile Listener listener;

        private Job(String playlistId, int total, Listener listener) {
            this.playlistId = playlistId;
            this.total = total;
            this.listener = listener;
        }

        public Phase   phase()   { return phase; }
        public int     total()   { return total; }
        public int     done()    { return done.get(); }
        public boolean running() { return !finished.get(); }

        /**
         * Point this job's callbacks at a new listener — used when the
         * Last.fm Manager is reopened while an update is still going.
         * Passing null detaches: the job keeps running and keeps
         * persisting, nothing is delivered to the UI.
         */
        public void listenWith(Listener l) { this.listener = l; }

        public void cancel() { cancelled.set(true); }

        private void beginPhase(Phase p, int phaseTotal) {
            this.phase = p;
            this.total = phaseTotal;
            this.done.set(0);
            fireProgress(0);
        }

        private void fireProgress(int d) {
            Listener l = listener;
            if (l == null) return;
            Phase p = phase;
            int t = total;
            Platform.runLater(() -> l.onProgress(p, d, t));
        }

        private void fireFinished(Result result) {
            finished.set(true);
            RUNNING.remove(playlistId, this);
            Listener l = listener;
            if (l != null) Platform.runLater(() -> l.onFinished(result));
        }
    }

    /** The in-flight update for this playlist, or null if none is running. */
    public static Job jobFor(String playlistId) {
        Job job = RUNNING.get(playlistId);
        return (job != null && job.running()) ? job : null;
    }

    /**
     * Start refreshing this playlist's Last.fm data. If an update is
     * already running for it, that one is returned with {@code listener}
     * attached instead of a second job being started.
     */
    public static Job start(String playlistId, List<Song> songs, Listener listener) {
        Job existing = jobFor(playlistId);
        if (existing != null) {
            existing.listenWith(listener);
            return existing;
        }

        Job job = new Job(playlistId, songs.size(), listener);
        RUNNING.put(playlistId, job);

        Thread coordinator = new Thread(() -> run(job, songs), "lastfm-playcount-update");
        coordinator.setDaemon(true);
        coordinator.start();
        return job;
    }

    /** Distinct primary artists in a playlist, keyed and de-duplicated, first credit kept for display. */
    public static Map<String, String> distinctArtists(List<Song> songs) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (Song s : songs) {
            String display = LastFmPlaycountLookup.primaryArtist(s.artist());
            String key = ArtistPlaycountStore.key(display);
            if (!key.isEmpty()) byKey.putIfAbsent(key, display);
        }
        return byKey;
    }

    // ==================================================================

    private static void run(Job job, List<Song> songs) {
        Map<String, Long> songResults   = Collections.synchronizedMap(new LinkedHashMap<>());
        Map<String, Long> artistResults = Collections.synchronizedMap(new LinkedHashMap<>());
        AtomicInteger failedSongs   = new AtomicInteger();
        AtomicInteger failedArtists = new AtomicInteger();

        try {
            job.beginPhase(Phase.SONGS, songs.size());
            runSongs(job, songs, songResults, failedSongs);

            Map<String, String> artists = distinctArtists(songs);
            job.beginPhase(Phase.ARTISTS, artists.size());
            runArtists(job, artists, artistResults, failedArtists);
        } finally {
            // Copy under each map's own lock: on the interrupted path a
            // worker can still be mid-put, and copying a synchronizedMap
            // without holding it is a ConcurrentModificationException
            // waiting to happen.
            Map<String, Long> songSnapshot;
            Map<String, Long> artistSnapshot;
            synchronized (songResults)   { songSnapshot   = new HashMap<>(songResults); }
            synchronized (artistResults) { artistSnapshot = new HashMap<>(artistResults); }
            job.fireFinished(new Result(songSnapshot, artistSnapshot,
                    failedSongs.get(), failedArtists.get(), job.cancelled.get()));
        }
    }

    private static void runSongs(Job job, List<Song> songs,
                                 Map<String, Long> results, AtomicInteger failed) {
        Map<String, Long> pending = Collections.synchronizedMap(new LinkedHashMap<>());
        forEachInPool(songs, song -> {
            long playcount = LastFmPlaycountLookup.resolveBlocking(song);
            if (playcount == LastFmPlaycountLookup.FAILED) {
                failed.incrementAndGet();
            } else {
                results.put(song.id(), playcount);
                pending.put(song.id(), playcount);
            }
            job.fireProgress(job.done.incrementAndGet());
            if (isFull(pending)) flushSongs(pending);
        }, job, failed);
        flushSongs(pending);
    }

    private static void runArtists(Job job, Map<String, String> artists,
                                   Map<String, Long> results, AtomicInteger failed) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(artists.entrySet());
        Map<String, ArtistPlaycountStore.ArtistPlays> pending =
                Collections.synchronizedMap(new LinkedHashMap<>());

        forEachInPool(entries, entry -> {
            String key = entry.getKey();
            String display = entry.getValue();
            Long playcount = null;
            try {
                playcount = LastFmClient.fetchLinkedArtistPlaycount(display).orElse(0L);
            } catch (Exception e) {
                // Retries are exhausted inside the client; nothing left to try.
            }
            if (playcount == null) {
                failed.incrementAndGet();
            } else {
                results.put(key, playcount);
                pending.put(key, new ArtistPlaycountStore.ArtistPlays(key, display, playcount));
            }
            job.fireProgress(job.done.incrementAndGet());
            if (isFull(pending)) flushArtists(pending);
        }, job, failed);
        flushArtists(pending);
    }

    /**
     * Run {@code work} over every item on the fetch pool and block until
     * they're all accounted for. Cancelled items short-circuit but still
     * count down, so a cancel drains promptly instead of waiting out the
     * whole queue.
     */
    private static <T> void forEachInPool(List<T> items, java.util.function.Consumer<T> work,
                                          Job job, AtomicInteger failed) {
        if (items.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(FETCH_THREADS, daemonThreads());
        CountDownLatch latch = new CountDownLatch(items.size());
        try {
            for (T item : items) {
                pool.submit(() -> {
                    try {
                        if (job.cancelled.get()) return;
                        work.accept(item);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isFull(Map<?, ?> pending) {
        synchronized (pending) { return pending.size() >= FLUSH_EVERY; }
    }

    private static void flushSongs(Map<String, Long> pending) {
        Map<String, Long> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        PlaycountStore.saveAll(batch);
    }

    private static void flushArtists(Map<String, ArtistPlaycountStore.ArtistPlays> pending) {
        List<ArtistPlaycountStore.ArtistPlays> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending.values());
            pending.clear();
        }
        ArtistPlaycountStore.saveAll(batch);
    }

    private static ThreadFactory daemonThreads() {
        return r -> {
            Thread t = new Thread(r, "lastfm-playcount-update-worker");
            t.setDaemon(true);
            return t;
        };
    }
}
