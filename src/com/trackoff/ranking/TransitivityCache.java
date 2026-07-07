package com.trackoff.ranking;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Directed "is preferred over" graph built from user answers.
 * Now supports {@link #copy()} / {@link #replaceWith(TransitivityCache)}
 * so callers (the ranker's undo stack) can snapshot and restore it.
 */
public final class TransitivityCache {

    private final Map<String, Set<String>> preferredOver = new HashMap<>();

    public void recordPreference(String winner, String loser) {
        preferredOver.computeIfAbsent(winner, k -> new HashSet<>()).add(loser);
    }

    public boolean isPreferredOver(String a, String b) {
        return reachable(a, b, new HashSet<>());
    }

    private boolean reachable(String from, String target, Set<String> visited) {
        if (!visited.add(from)) return false;
        Set<String> direct = preferredOver.get(from);
        if (direct == null) return false;
        if (direct.contains(target)) return true;
        for (String next : direct) {
            if (reachable(next, target, visited)) return true;
        }
        return false;
    }

    public int size() { return preferredOver.size(); }

    /** Deep copy — the returned cache shares no mutable state with this one. */
    public TransitivityCache copy() {
        TransitivityCache c = new TransitivityCache();
        for (Map.Entry<String, Set<String>> e : preferredOver.entrySet()) {
            c.preferredOver.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        return c;
    }

    /** Overwrite this cache's contents with a deep copy of {@code other}. */
    public void replaceWith(TransitivityCache other) {
        preferredOver.clear();
        for (Map.Entry<String, Set<String>> e : other.preferredOver.entrySet()) {
            preferredOver.put(e.getKey(), new HashSet<>(e.getValue()));
        }
    }
}