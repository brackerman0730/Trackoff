package com.rankify.ui;

import com.rankify.io.CsvPlaylistSource;
import com.rankify.io.PlaylistSource;
import com.rankify.io.ProgressStore;
import com.rankify.io.SpotifyPlaylistSource;
import com.rankify.model.Playlist;
import com.rankify.ranking.AdaptiveMergeSortRanker;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class MainView {

    private static final Path CRED_FILE =
            Paths.get(System.getProperty("user.home"), ".rankify", "spotify.txt");

    private final Stage stage;

    public MainView(Stage stage) { this.stage = stage; }

    public void show() {
        Label title = new Label("Rankify");
        title.getStyleClass().add("label-title");

        Label subtitle = new Label("Rank your Spotify playlists, one pair at a time");
        subtitle.getStyleClass().add("label-subtitle");

        Button loadCsv    = primaryButton("Load playlist CSV");
        Button spotifyBtn = secondaryButton("Load from Spotify URL");
        Button tierBtn    = secondaryButton("Skip ranking → Tier List from CSV");
        Button swipeBtn = secondaryButton("Swipe (Keep / Delete)");
        Button resumeBtn  = ghostButton("Resume saved session");
        Button credsBtn   = ghostButton("Set Spotify credentials");
        loadCsv   .setOnAction(e -> chooseAndStart());
        spotifyBtn.setOnAction(e -> loadFromSpotify());
        tierBtn   .setOnAction(e -> loadCsvForTierList());
        swipeBtn.setOnAction(e -> startSwipe());
        resumeBtn .setOnAction(e -> chooseAndResume());
        credsBtn  .setOnAction(e -> promptForCredentials(true));

        for (Button b : new Button[]{loadCsv, spotifyBtn, tierBtn, swipeBtn, resumeBtn, credsBtn}) {
            b.setMaxWidth(320);
            b.setPrefHeight(46);
        }

        Region spacer = new Region();
        spacer.setPrefHeight(20);

        VBox root = new VBox(12, title, subtitle, spacer,
                             loadCsv, spotifyBtn, tierBtn, swipeBtn, resumeBtn, credsBtn);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(60));

        Scene scene = new Scene(root, 600, 540);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Rankify");
        stage.show();
    }

    // ---- Button factories ----
    private Button primaryButton(String t)   { Button b = new Button(t); b.getStyleClass().add("button-primary");   return b; }
    private Button secondaryButton(String t) { Button b = new Button(t); b.getStyleClass().add("button-secondary"); return b; }
    private Button ghostButton(String t)     { Button b = new Button(t); b.getStyleClass().add("button-ghost");     return b; }

    // ---- Actions ----
    private void chooseAndStart() {
        File f = csvChooser("Select playlist file").showOpenDialog(stage);
        if (f == null) return;
        try {
            PlaylistSource source = new CsvPlaylistSource();
            Playlist playlist = source.load(f.getAbsolutePath());
            startRanking(playlist);
        } catch (Exception ex) {
            error("Couldn't load playlist: " + ex.getMessage());
        }
    }

    /** Skip pairwise ranking and jump straight to the tier-list UI. */
    private void loadCsvForTierList() {
        File f = csvChooser("Select playlist file").showOpenDialog(stage);
        if (f == null) return;
        try {
            Playlist playlist = new CsvPlaylistSource().load(f.getAbsolutePath());
            if (playlist.size() < 1) { info("Empty playlist."); return; }
            new TierListView(stage, playlist, playlist.songs()).show();
        } catch (Exception ex) {
            error("Couldn't load playlist: " + ex.getMessage());
        }
    }
    /**
     * Swipe mode. Asks the user whether they want to load a local CSV or
     * pull a playlist from a Spotify URL — Spotify imports carry preview
     * URLs and album art, so those cards auto-play while they're on top.
     */
    private void startSwipe() {
        Alert choose = new Alert(Alert.AlertType.CONFIRMATION);
        choose.setTitle("Swipe");
        choose.setHeaderText("Where should the playlist come from?");
        choose.setContentText("Spotify imports include album art and 30s previews.");

        javafx.scene.control.ButtonType csvBtn     = new javafx.scene.control.ButtonType("CSV file");
        javafx.scene.control.ButtonType spotifyBtn = new javafx.scene.control.ButtonType("Spotify URL");
        javafx.scene.control.ButtonType cancelBtn  =
                new javafx.scene.control.ButtonType("Cancel", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        choose.getButtonTypes().setAll(csvBtn, spotifyBtn, cancelBtn);
        Theme.apply(choose.getDialogPane().getScene());

        var pick = choose.showAndWait().orElse(cancelBtn);
        if (pick == csvBtn)          swipeFromCsv();
        else if (pick == spotifyBtn) swipeFromSpotify();
    }

    private void swipeFromCsv() {
        File f = csvChooser("Select playlist file").showOpenDialog(stage);
        if (f == null) return;
        try {
            Playlist playlist = new CsvPlaylistSource().load(f.getAbsolutePath());
            if (playlist.size() < 1) { info("Empty playlist."); return; }
            new SwipeView(stage, playlist, playlist.songs()).show();
        } catch (Exception ex) {
            error("Couldn't load playlist: " + ex.getMessage());
        }
    }

    private void swipeFromSpotify() {
        String[] creds = loadCredentials();
        if (creds == null) {
            info("You need to set your Spotify Client ID and Secret first.");
            creds = promptForCredentials(false);
            if (creds == null) return;
        }

        TextInputDialog urlDialog = new TextInputDialog();
        urlDialog.setTitle("Load Spotify Playlist");
        urlDialog.setHeaderText("Paste a Spotify playlist URL or ID");
        urlDialog.setContentText("URL:");
        urlDialog.getDialogPane().setPrefWidth(500);
        Theme.apply(urlDialog.getDialogPane().getScene());
        String url = urlDialog.showAndWait().orElse("").trim();
        if (url.isEmpty()) return;

        try {
            Playlist playlist = new SpotifyPlaylistSource(creds[0], creds[1]).load(url);
            if (playlist.size() < 1) { info("Empty playlist."); return; }
            new SwipeView(stage, playlist, playlist.songs()).show();
        } catch (Exception ex) {
            error("Spotify load failed: " + ex.getMessage());
        }
    }
    private void loadFromSpotify() {
        String[] creds = loadCredentials();
        if (creds == null) {
            info("You need to set your Spotify Client ID and Secret first.");
            creds = promptForCredentials(false);
            if (creds == null) return;
        }

        TextInputDialog urlDialog = new TextInputDialog();
        urlDialog.setTitle("Load Spotify Playlist");
        urlDialog.setHeaderText("Paste a Spotify playlist URL or ID");
        urlDialog.setContentText("URL:");
        urlDialog.getDialogPane().setPrefWidth(500);
        Theme.apply(urlDialog.getDialogPane().getScene());
        String url = urlDialog.showAndWait().orElse("").trim();
        if (url.isEmpty()) return;

        try {
            PlaylistSource source = new SpotifyPlaylistSource(creds[0], creds[1]);
            Playlist playlist = source.load(url);
            startRanking(playlist);
        } catch (Exception ex) {
            error("Spotify load failed: " + ex.getMessage());
        }
    }

    private String[] promptForCredentials(boolean allowOverwrite) {
        String[] existing = loadCredentials();
        if (existing != null && !allowOverwrite) return existing;

        TextInputDialog idDialog = new TextInputDialog(existing == null ? "" : existing[0]);
        idDialog.setTitle("Spotify Credentials");
        idDialog.setHeaderText("Enter your Spotify Client ID");
        idDialog.setContentText("Client ID:");
        idDialog.getDialogPane().setPrefWidth(500);
        Theme.apply(idDialog.getDialogPane().getScene());
        String id = idDialog.showAndWait().orElse("").trim();
        if (id.isEmpty()) return null;

        TextInputDialog secretDialog = new TextInputDialog(existing == null ? "" : existing[1]);
        secretDialog.setTitle("Spotify Credentials");
        secretDialog.setHeaderText("Enter your Spotify Client Secret");
        secretDialog.setContentText("Client Secret:");
        secretDialog.getDialogPane().setPrefWidth(500);
        Theme.apply(secretDialog.getDialogPane().getScene());
        String secret = secretDialog.showAndWait().orElse("").trim();
        if (secret.isEmpty()) return null;

        saveCredentials(id, secret);
        info("Credentials saved.");
        return new String[]{id, secret};
    }

    private String[] loadCredentials() {
        try {
            if (!Files.exists(CRED_FILE)) return null;
            List<String> lines = Files.readAllLines(CRED_FILE);
            if (lines.size() < 2) return null;
            return new String[]{ lines.get(0).trim(), lines.get(1).trim() };
        } catch (Exception e) { return null; }
    }

    private void saveCredentials(String id, String secret) {
        try {
            Files.createDirectories(CRED_FILE.getParent());
            Files.writeString(CRED_FILE, id + "\n" + secret + "\n");
        } catch (Exception e) {
            error("Couldn't save credentials: " + e.getMessage());
        }
    }

    private void chooseAndResume() {
        File playlistFile = csvChooser("Select the ORIGINAL playlist file").showOpenDialog(stage);
        if (playlistFile == null) return;
        File sessionFile = sessionChooser("Select saved session (.rkfy)").showOpenDialog(stage);
        if (sessionFile == null) return;

        try {
            Playlist playlist = new CsvPlaylistSource().load(playlistFile.getAbsolutePath());
            AdaptiveMergeSortRanker ranker = new AdaptiveMergeSortRanker(playlist);
            new ProgressStore().load(Paths.get(sessionFile.getAbsolutePath()), ranker);
            new ComparisonView(stage, playlist, ranker).show();
        } catch (Exception ex) {
            error("Couldn't resume: " + ex.getMessage());
        }
    }

    private void startRanking(Playlist playlist) {
        if (playlist.size() < 2) {
            info("Playlist needs at least two songs to rank.");
            return;
        }
        AdaptiveMergeSortRanker ranker = new AdaptiveMergeSortRanker(playlist);
        new ComparisonView(stage, playlist, ranker).show();
    }

    private FileChooser csvChooser(String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV / TXT", "*.csv", "*.txt"));
        return fc;
    }

    private FileChooser sessionChooser(String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Rankify session (*.rkfy)", "*.rkfy"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );
        return fc;
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