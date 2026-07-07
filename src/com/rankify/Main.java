package com.rankify;

import com.rankify.ui.MainView;
import javafx.application.Application;
import javafx.stage.Stage;

public final class Main extends Application {

    @Override
    public void start(Stage stage) {
        try {
            com.rankify.db.Database.init();
        } catch (Exception e) {
            javafx.scene.control.Alert a = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR,
                    "Couldn't open Trackoff database:\n" + e.getMessage());
            a.showAndWait();
            javafx.application.Platform.exit();
            return;
        }
        new MainView(stage).show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}