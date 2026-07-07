package com.trackoff.ui;

import com.trackoff.io.CsvPlaylistSource;
import com.trackoff.io.PlaylistSource;
import com.trackoff.io.ProgressStore;
import com.trackoff.io.SpotifyPlaylistSource;
import com.trackoff.io.lastfm.LastFmClient;
import com.trackoff.io.spotify.TokenStore;
import com.trackoff.model.Playlist;
import com.trackoff.ranking.AdaptiveMergeSortRanker;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Paths;

public final class MainView {

    private final Stage stage;

    public MainView(Stage stage) { this.stage = stage; }

    public void show() {
        Label title = new Label("Trackoff");
        title.getStyleClass().add("label-title");

        Label subtitle = new Label("Rank, sort, and manage your Spotify playlists");
        subtitle.getStyleClass().add("label-subtitle");

        // ---- Library button (star of the show) ----
        Button libraryBtn = primaryButton("My Library");
        boolean linked = TokenStore.isLinked();
        libraryBtn.setDisable(!linked);
        if (!linked) {
            libraryBtn.setTooltip(new Tooltip(
                    "Connect your Spotify account first (button below)."));
        }
        libraryBtn.setOnAction(e -> new LibraryView(stage).show());

        // ---- Ranking / tier / swipe entry points ----
        Button loadCsv    = secondaryButton("Load playlist CSV");
        Button spotifyBtn = secondaryButton("Load from Spotify URL");
        Button tierBtn    = secondaryButton("Skip ranking → Tier List from CSV");
        Button swipeBtn   = secondaryButton("Swipe (Keep / Delete)");
        Button resumeBtn  = ghostButton("Resume saved session");

        loadCsv   .setOnAction(e -> chooseAndStart());
        spotifyBtn.setOnAction(e -> loadFromSpotifyUrl());
        tierBtn   .setOnAction(e -> loadCsvForTierList());
        swipeBtn  .setOnAction(e -> startSwipe());
        resumeBtn .setOnAction(e -> chooseAndResume());

        // ---- Account connection buttons ----
        Button spotifyAcctBtn = ghostButton(
                TokenStore.isLinked() ? "Spotify account: connected"
                                      : "Connect Spotify account");
        Button lastfmAcctBtn  = ghostButton(
                LastFmClient.isLinked() ? "Last.fm account: connected"
                                        : "Connect Last.fm account");

        spotifyAcctBtn.setOnAction(e -> new SpotifyConnectView(stage).show());
        lastfmAcctBtn .setOnAction(e -> new LastFmConnectView(stage).show());

        // ---- Sizing ----
        Button[] all = {
                libraryBtn, loadCsv, spotifyBtn, tierBtn, swipeBtn, resumeBtn,
                spotifyAcctBtn, lastfmAcctBtn
        };
        for (Button b : all) {
            b.setMaxWidth(320);
            b.setPrefHeight(44);
        }

        Region spacer1 = new Region(); spacer1.setPrefHeight(14);
        Region spacer2 = new Region(); spacer2.setPrefHeight(14);

        VBox root = new VBox(10,
                title, subtitle,
                spacer1,
                libraryBtn,
                spacer2,
                loadCsv, spotifyBtn, tierBtn, swipeBtn, resumeBtn,
                new Region() {{ setPrefHeight(14); }},
                spotifyAcctBtn, lastfmAcctBtn);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(50));

        Scene scene = new Scene(root, 620, 700);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Trackoff");
        stage.show();
    }

    // ---- Button factories ----
    private Button primaryButton(String t)   { Button b = new Button(t); b.getStyleClass().add("button-primary");   return b; }
    private Button secondaryButton(String t) { Button b = new Button(t); b.getStyleClass().add("button-secondary"); return b; }
    private Button ghostButton(String t)     { Button b = new Button(t); b.getStyleClass().add("button-ghost");     return b; }

    // ==================================================================
    //  Ranking / tier / swipe flows (unchanged from before)
    // ==================================================================

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

    private void loadFromSpotifyUrl() {
        // Uses the existing Client Credentials flow — kept intact so
        // public playlists work without OAuth. OAuth users can use
        // "My Library" instead.
        String[] creds = readClientCreds();
        if (creds == null) {
            info("Set your Spotify Client ID + Secret first "
                    + "(bottom of the main menu).");
            return;
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

    private void startSwipe() {
        Alert choose = new Alert(Alert.AlertType.CONFIRMATION);
        choose.setTitle("Swipe");
        choose.setHeaderText("Where should the playlist come from?");
        choose.setContentText("Spotify imports include album art and 30s previews.");

        ButtonType csvBtn     = new ButtonType("CSV file");
        ButtonType spotifyBtn = new ButtonType("Spotify URL");
        ButtonType cancelBtn  = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
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
        String[] creds = readClientCreds();
        if (creds == null) {
            info("Set your Spotify Client ID + Secret first "
                    + "(bottom of the main menu).");
            return;
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

    private void chooseAndResume() {
        File playlistFile = csvChooser("Select the ORIGINAL playlist file").showOpenDialog(stage);
        if (playlistFile == null) return;
        File sessionFile = sessionChooser("Select saved session (.trackoff)").showOpenDialog(stage);
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

    // ==================================================================
    //  Helpers
    // ==================================================================

    /** Read Client ID + Secret from settings (SQLite). Returns null if unset. */
    private String[] readClientCreds() {
        var id = com.trackoff.config.Settings.get(com.trackoff.config.Settings.SPOTIFY_CLIENT_ID);
        var sc = com.trackoff.config.Settings.get(com.trackoff.config.Settings.SPOTIFY_CLIENT_SECRET);
        if (id.isEmpty() || sc.isEmpty()) return null;
        return new String[]{ id.get(), sc.get() };
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
                new FileChooser.ExtensionFilter("trackoff session (*.trackoff)", "*.trackoff"),
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