package com.rankify.ui;

import com.rankify.io.RankingCsvWriter;
import com.rankify.model.Playlist;
import com.rankify.model.Song;
import com.rankify.ranking.AdaptiveMergeSortRanker;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

public final class ResultView {

    private final Stage    stage;
    private final Playlist playlist;
    private final List<Song> ranking;
    private final AdaptiveMergeSortRanker ranker;

    public ResultView(Stage stage, Playlist playlist, List<Song> ranking,
                      AdaptiveMergeSortRanker ranker) {
        this.stage    = stage;
        this.playlist = playlist;
        this.ranking  = ranking;
        this.ranker   = ranker;
    }

    public void show() {
        Label header = new Label("Your ranking is ready 🎉");
        header.getStyleClass().add("label-header");

        int removedCount = ranker.removedIds().size();
        Label stats = new Label(String.format(
                "%d songs ranked using %d direct comparisons (%d more inferred).%s",
                ranking.size(),
                ranker.comparisonsAsked(),
                ranker.comparisonsSavedByInference(),
                removedCount > 0
                    ? String.format("  %d unknown song%s excluded.",
                                    removedCount, removedCount == 1 ? "" : "s")
                    : ""));
        stats.getStyleClass().add("label-stats");

        TableView<Row> table = new TableView<>();
        table.setItems(FXCollections.observableArrayList(toRows(ranking)));
        table.getColumns().addAll(
                col("#",      "rank",   55),
                col("Song",   "title",  280),
                col("Artist", "artist", 200),
                col("Album",  "album",  240)
        );
        table.setPlaceholder(new Label("No songs ranked."));

        Button export   = primaryButton("Export as CSV");
        Button tierList = secondaryButton("Create Tier List");
        Button back     = ghostButton("Back to start");
        export .setOnAction(e -> exportCsv());
        tierList.setOnAction(e -> new TierListView(stage, playlist, ranking).show());
        back    .setOnAction(e -> new MainView(stage).show());

        HBox actions = new HBox(15, export, tierList, back);
        actions.setAlignment(Pos.CENTER);

        VBox root = new VBox(18, header, stats, table, actions);
        root.setPadding(new Insets(30));
        VBox.setVgrow(table, Priority.ALWAYS);

        Scene scene = new Scene(root, 900, 640);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Rankify — Results");
    }

    @SuppressWarnings("unchecked")
    private <T> TableColumn<Row, T> col(String name, String prop, double width) {
        TableColumn<Row, T> c = new TableColumn<>(name);
        c.setCellValueFactory(new PropertyValueFactory<>(prop));
        c.setPrefWidth(width);
        return c;
    }

    private List<Row> toRows(List<Song> ranking) {
        List<Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < ranking.size(); i++) {
            Song s = ranking.get(i);
            rows.add(new Row(i + 1, s.title(), s.artist(), s.album()));
        }
        return rows;
    }

    private void exportCsv() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save ranking CSV");
        fc.setInitialFileName(playlist.name() + " (ranked).csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;

        try {
            new RankingCsvWriter().write(Paths.get(file.getAbsolutePath()), ranking);
            Alert a = new Alert(Alert.AlertType.INFORMATION, "Exported to " + file.getName());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        } catch (Exception ex) {
            Alert a = new Alert(Alert.AlertType.ERROR, "Export failed: " + ex.getMessage());
            Theme.apply(a.getDialogPane().getScene());
            a.showAndWait();
        }
    }

    private Button primaryButton(String t) {
        Button b = new Button(t); b.getStyleClass().add("button-primary");
        b.setPrefHeight(44); b.setPrefWidth(180); return b;
    }
    private Button secondaryButton(String t) {
        Button b = new Button(t); b.getStyleClass().add("button-secondary");
        b.setPrefHeight(44); b.setPrefWidth(180); return b;
    }
    private Button ghostButton(String t) {
        Button b = new Button(t); b.getStyleClass().add("button-ghost");
        b.setPrefHeight(44); b.setPrefWidth(180); return b;
    }

    public static class Row {
        private final int rank;
        private final String title, artist, album;
        public Row(int rank, String title, String artist, String album) {
            this.rank = rank; this.title = title; this.artist = artist; this.album = album;
        }
        public int    getRank()   { return rank; }
        public String getTitle()  { return title; }
        public String getArtist() { return artist; }
        public String getAlbum()  { return album; }
    }
}