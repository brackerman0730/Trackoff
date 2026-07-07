package com.trackoff.ui;

import com.trackoff.config.Settings;
import com.trackoff.io.spotify.SpotifyAuth;
import com.trackoff.io.spotify.TokenStore;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Two-step Spotify connection screen:
 *   1. Confirm Client ID / Secret (pre-filled if we already have them)
 *   2. "Connect" launches the browser OAuth flow
 *
 * We keep it its own view rather than a chain of TextInputDialogs so
 * users can see both fields at once and correct typos before clicking.
 */
public final class SpotifyConnectView {

    private final Stage stage;

    public SpotifyConnectView(Stage stage) { this.stage = stage; }

    public void show() {
        Label title = new Label("Connect Spotify");
        title.getStyleClass().add("label-header");

        Label help = new Label(
                "Trackoff uses Spotify's official API. You need a free Spotify "
              + "developer app — create one at developer.spotify.com/dashboard "
              + "and paste its Client ID and Secret below. Also add these three "
              + "URIs to the app's \"Redirect URIs\" list:\n\n"
              + "    http://127.0.0.1:47821/callback\n"
              + "    http://127.0.0.1:47822/callback\n"
              + "    http://127.0.0.1:47823/callback");
        help.getStyleClass().add("label-muted");
        help.setWrapText(true);
        help.setMaxWidth(520);

        TextField idField = new TextField(Settings.getOr(Settings.SPOTIFY_CLIENT_ID, ""));
        idField.setPromptText("Client ID");
        idField.setPrefWidth(420);

        PasswordField secretField = new PasswordField();
        secretField.setText(Settings.getOr(Settings.SPOTIFY_CLIENT_SECRET, ""));
        secretField.setPromptText("Client Secret");
        secretField.setPrefWidth(420);

        Label status = new Label(currentStatus());
        status.getStyleClass().add("label-muted");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(22, 22);
        spinner.setVisible(false);

        Button connectBtn = new Button("Connect via browser");
        connectBtn.getStyleClass().add("button-primary");

        Button logoutBtn = new Button("Disconnect");
        logoutBtn.getStyleClass().add("button-ghost");
        logoutBtn.setDisable(!TokenStore.isLinked());

        Button back = new Button("Back");
        back.getStyleClass().add("button-ghost");
        back.setOnAction(e -> new MainView(stage).show());

        // ---- actions ----
        connectBtn.setOnAction(e -> {
            String id = idField.getText().trim();
            String secret = secretField.getText().trim();
            if (id.isEmpty() || secret.isEmpty()) {
                error("Enter both Client ID and Client Secret.");
                return;
            }
            Settings.set(Settings.SPOTIFY_CLIENT_ID, id);
            Settings.set(Settings.SPOTIFY_CLIENT_SECRET, secret);

            connectBtn.setDisable(true);
            back.setDisable(true);
            spinner.setVisible(true);
            status.setText("Waiting for browser… complete the login in your browser.");

            // Run the blocking OAuth call off the FX thread.
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    SpotifyAuth.login();
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                spinner.setVisible(false);
                info("Spotify connected!");
                new MainView(stage).show();
            });
            task.setOnFailed(ev -> {
                spinner.setVisible(false);
                connectBtn.setDisable(false);
                back.setDisable(false);
                Throwable t = task.getException();
                status.setText("Connection failed.");
                error("Spotify login failed:\n" + (t == null ? "unknown" : t.getMessage()));
            });
            new Thread(task, "spotify-oauth").start();
        });

        logoutBtn.setOnAction(e -> {
            TokenStore.clear();
            logoutBtn.setDisable(true);
            status.setText(currentStatus());
            info("Disconnected from Spotify.");
        });

        HBox statusRow = new HBox(10, spinner, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        HBox buttonRow = new HBox(10, connectBtn, logoutBtn, back);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        Region gap = new Region();
        gap.setPrefHeight(8);

        VBox form = new VBox(10,
                new Label("Client ID"),     idField,
                new Label("Client Secret"), secretField,
                gap,
                statusRow, buttonRow);
        form.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(18, title, help, form);
        root.setPadding(new Insets(30));
        root.setAlignment(Pos.TOP_LEFT);

        Scene scene = new Scene(root, 640, 560);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Trackoff — Connect Spotify");
    }

    private static String currentStatus() {
        return TokenStore.isLinked()
                ? "Currently connected."
                : "Not connected yet.";
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg);
        Theme.apply(a.getDialogPane().getScene());
        a.showAndWait();
    }
    private void error(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg);
        Theme.apply(a.getDialogPane().getScene());
        a.showAndWait();
    }
}