package com.trackoff.io;

import com.trackoff.io.deezer.DeezerPreviewSource;
import com.trackoff.model.Song;

import javafx.application.Platform;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * Shared async/caching front door for song preview lookups, used by both
 * ComparisonView and SwipeView so they don't each re-implement the same
 * "don't block the FX thread, don't re-query the same song" logic.
 *
 * Cache is keyed by song id; an empty string means "looked up, nothing
 * found" so a miss doesn't get re-queried every time the song reappears
 * (which happens a lot — the ranker re-compares the same songs).
 */
public final class PreviewLookup {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, daemonThreadFactory());

    private PreviewLookup() {}

    /** Resolve (from cache or via Deezer) and deliver the result on the FX thread. */
    public static void resolveAsync(Song song, Consumer<Optional<String>> onResolved) {
        String cached = CACHE.get(song.id());
        if (cached != null) {
            onResolved.accept(cached.isEmpty() ? Optional.empty() : Optional.of(cached));
            return;
        }

        EXECUTOR.submit(() -> {
            String found = "";
            try {
                found = DeezerPreviewSource.findPreviewUrl(song.artist(), song.title()).orElse("");
            } catch (Exception ignored) {
                // Best-effort: any lookup failure just means no preview, same as before.
            }
            CACHE.put(song.id(), found);
            String result = found;
            Platform.runLater(() -> onResolved.accept(result.isEmpty() ? Optional.empty() : Optional.of(result)));
        });
    }

    private static ThreadFactory daemonThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "preview-lookup");
            t.setDaemon(true);
            return t;
        };
    }
}
