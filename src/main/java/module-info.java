module by.snql.filescanner {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.logging;
    requires com.google.gson;
    requires org.apache.pdfbox;

    exports by.snql.filescanner;
    exports by.snql.filescanner.model;
    exports by.snql.filescanner.scanner;
    exports by.snql.filescanner.config;
    exports by.snql.filescanner.core.analysis;
    exports by.snql.filescanner.core.cleanup;
    exports by.snql.filescanner.core.export;
    exports by.snql.filescanner.core.project;
    exports by.snql.filescanner.core.util;
    exports by.snql.filescanner.ui;

    opens by.snql.filescanner.config to com.google.gson;
    opens by.snql.filescanner.core.cleanup to com.google.gson;
}
