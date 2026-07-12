package com.trackoff.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

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

    /**
     * Show {@code root} as the current view on {@code stage}. Every view
     * used to build its own {@code new Scene(root, w, h)} and call
     * {@code stage.setScene(...)} — replacing the Scene object on every
     * navigation. Each view has a different preferred size, and swapping
     * the Scene made the window un-maximize/un-fullscreen and snap to
     * whatever size the new view happened to prefer (reported as the
     * window "shrinking" when navigating while maximized).
     *
     * The fix: create the Scene once, on first use, and every subsequent
     * call just swaps its root node — the Stage's size/maximized/
     * fullscreen state is untouched by a root swap, only by replacing
     * the Scene itself. {@code prefWidth}/{@code prefHeight} only matter
     * for that first call (they size the initial window).
     */
    public static void show(Stage stage, Parent root, double prefWidth, double prefHeight) {
        Scene scene = stage.getScene();
        if (scene == null) {
            scene = new Scene(root, prefWidth, prefHeight);
            apply(scene);
            stage.setScene(scene);
        } else {
            scene.setRoot(root);
        }
    }
}