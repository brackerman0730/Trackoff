package com.rankify;

import com.rankify.ui.MainView;
import javafx.application.Application;
import javafx.stage.Stage;

public final class Main extends Application {

    @Override
    public void start(Stage stage) {
        new MainView(stage).show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}