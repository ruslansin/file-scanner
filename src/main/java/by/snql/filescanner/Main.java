package by.snql.filescanner;

import by.snql.filescanner.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        var window = new MainWindow(stage);
        window.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
