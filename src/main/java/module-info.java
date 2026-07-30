module by.snql.filescanner {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires com.google.gson;
    requires org.apache.pdfbox;

    exports by.snql.filescanner;
    exports by.snql.filescanner.model;
    exports by.snql.filescanner.scanner;
    exports by.snql.filescanner.ui;

    opens by.snql.filescanner.ui to com.google.gson;
}
