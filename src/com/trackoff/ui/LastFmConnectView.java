package com.trackoff.ui;

import com.trackoff.config.Settings;
import com.trackoff.io.lastfm.LastFmClient;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URI;
import java.awt.Desktop;
/**
 * Ask the user for their Last.fm username + API key, verify with a
 * test call, save on success.
 *
 * Way simpler than Spotify â€” no OAuth, no redirect, no browser dance.
 * One HTTP request to {@code user.getinfo} tells us if the creds work
 * and gives us the scrobble count for a nice welcome message.
 */
public final class LastFmConnectView {

    private final Stage stage;

    public LastFmConnectView(Stage stage) { this.stage = stage; }

    public void show() {
        Label title = new Label("Connect Last.fm");
        title.getStyleClass().add("label-header");

        Label help = new Label(
                "Trackoff reads your Last.fm scrobbles to show play counts and "
              + "power \"most listened\" sorting. Grab a free API key here:");
        help.getStyleClass().add("label-muted");
        help.setWrapText(true);
        help.setMaxWidth(520);

        Hyperlink apiLink = new Hyperlink("https://www.last.fm/api/account/create");
        apiLink.setOnAction(e -> {
            try {
                Desktop.getDesktop().browse(URI.create("https://www.last.fm/api/account/create"));
            } catch (Exception ex) { /* ignore */ }
        });

        TextField userField = new TextField(Settings.getOr(Settings.LASTFM_USERNAME, ""));
        userField.setPromptText("Last.fm username");
        userField.setPrefWidth(420);

        TextField keyField = new TextField(Settings.getOr(Settings.LASTFM_API_KEY, ""));
        keyField.setPromptText("API key");
        keyField.setPrefWidth(420);

        Label status = new Label(currentStatus());
        status.getStyleClass().add("label-muted");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(22, 22);
        spinner.setVisible(false);

        Button connectBtn = new Button("Test & save");
        connectBtn.getStyleClass().add("button-primary");

        Button logoutBtn = new Button("Disconnect");
        logoutBtn.getStyleClass().add("button-ghost");
        logoutBtn.setDisable(!LastFmClient.isLinked());

        Button back = new Button("Back");
        back.getStyleClass().add("button-ghost");
        back.setOnAction(e -> new MainView(stage).show());

        connectBtn.setOnAction(e -> {
            String user = userField.getText().trim();
            String key  = keyField.getText().trim();
            if (user.isEmpty() || key.isEmpty()) {
                error("Enter both a username and API key.");
                return;
            }
            connectBtn.setDisable(true);
            back.setDisable(true);
            spinner.setVisible(true);
            status.setText("Testing credentialsâ€¦");

            Task<LastFmClient.UserInfo> task = new Task<>() {
                @Override protected LastFmClient.UserInfo call() throws Exception {
                    return LastFmClient.fetchUserInfo(user, key);
                }
            };
            task.setOnSucceeded(ev -> {
                spinner.setVisible(false);
                LastFmClient.UserInfo info = task.getValue();
                LastFmClient.saveCredentials(info.username(), key, info.playcount());
                info("Linked as " + info.username()
                        + "  Â·  " + String.format("%,d", info.playcount()) + " scrobbles");
                new MainView(stage).show();
            });
            task.setOnFailed(ev -> {
                spinner.setVisible(false);
                connectBtn.setDisable(false);
                back.setDisable(false);
                Throwable t = task.getException();
                status.setText("Failed.");
                error("Last.fm rejected the credentials:\n"
                        + (t == null ? "unknown" : t.getMessage()));
            });
            new Thread(task, "lastfm-verify").start();
        });

        logoutBtn.setOnAction(e -> {
            LastFmClient.clearCredentials();
            logoutBtn.setDisable(true);
            status.setText(currentStatus());
            info("Disconnected from Last.fm.");
        });

        HBox statusRow = new HBox(10, spinner, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        HBox buttonRow = new HBox(10, connectBtn, logoutBtn, back);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(10,
                new Label("Username"),  userField,
                new Label("API key"),   keyField,
                statusRow, buttonRow);
        form.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(18, title, help, apiLink, form);
        root.setPadding(new Insets(30));
        root.setAlignment(Pos.TOP_LEFT);

        Scene scene = new Scene(root, 640, 520);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Trackoff â€” Connect Last.fm");
    }

    private static String currentStatus() {
        if (!LastFmClient.isLinked()) return "Not connected.";
        String user = Settings.getOr(Settings.LASTFM_USERNAME, "?");
        String cnt  = Settings.getOr(Settings.LASTFM_PLAYCOUNT, "0");
        try {
            long n = Long.parseLong(cnt);
            return "Connected as " + user + "  Â·  " + String.format("%,d", n) + " scrobbles";
        } catch (Exception e) {
            return "Connected as " + user;
        }
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
