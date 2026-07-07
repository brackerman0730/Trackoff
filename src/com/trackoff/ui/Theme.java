package com.trackoff.ui;

import javafx.scene.Scene;

import java.io.File;

/**
 * Central place to apply the Spotify-inspired stylesheet to every scene.
 * Keeping this in one method means we can restyle the whole app by
 * editing exactly one file: styles.css in the project root.
 */
public final class Theme {

    private static final String CSS_FILE = "styles.css";

    private Theme() { }

    public static void apply(Scene scene) {
        File css = new File(CSS_FILE);
        if (css.exists()) {
            scene.getStylesheets().add(css.toURI().toString());
        } else {
            System.err.println("[Theme] styles.css not found — using default theme.");
        }
    }
}