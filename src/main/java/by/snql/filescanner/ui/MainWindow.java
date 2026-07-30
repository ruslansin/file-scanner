package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import by.snql.filescanner.scanner.FileScanner;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

public class MainWindow {

    private final Stage stage;
    private final FileScanner scanner;
    private final TreemapChart treemapChart;
    private final TreeView<FileNode> treeView;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private final Label sizeLabel;
    private final Button scanButton;
    private final Button cancelButton;

    private FileNode currentRoot;
    private Path scannedRootPath;

    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB", "TB"};

    public MainWindow(Stage stage) {
        this.stage = stage;
        this.scanner = new FileScanner();
        this.treemapChart = new TreemapChart();

        treeView = buildTreeView();
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        statusLabel = new Label("Select a folder to scan");
        sizeLabel = new Label("");
        scanButton = new Button("Scan Folder");
        cancelButton = new Button("Cancel");
        cancelButton.setVisible(false);

        scanButton.setOnAction(e -> chooseAndScan());
        cancelButton.setOnAction(e -> scanner.cancel());

        treemapChart.setOnNodeClicked(this::onTreemapNodeClicked);

        var toolbar = new HBox(10, scanButton, cancelButton, progressBar);
        toolbar.setPadding(new Insets(10));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        var statusBar = new HBox(10, statusLabel, sizeLabel);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.setStyle("-fx-background-color: #f0f0f0;");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        var leftPanel = treeView;
        leftPanel.setMinWidth(250);
        leftPanel.setPrefWidth(300);

        var splitPane = new SplitPane(leftPanel, treemapChart);
        splitPane.setDividerPositions(0.3);
        HBox.setHgrow(splitPane, Priority.ALWAYS);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        var root = new VBox(toolbar, splitPane, statusBar);
        var scene = new Scene(root, 1100, 750);
        scene.getStylesheets().add(getClass().getResource("/styles/main.css").toExternalForm());
        scene.addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        stage.setTitle("File Scanner — Disk Space Analyzer");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
    }

    private void onKeyPressed(KeyEvent e) {
        if (e.getCode() == KeyCode.DELETE) {
            var selected = treeView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue() != null) {
                deleteNode(selected.getValue());
            }
        }
    }

    private void deleteNode(FileNode node) {
        if (node.getPath() == null) return;

        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Deletion");
        alert.setHeaderText("Delete " + (node.isDirectory() ? "folder" : "file") + "?");
        alert.setContentText(node.getPath().toString() + "\n(" + formatSize(node.getSize()) + ")");

        var result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                deleteRecursive(node.getPath());
                statusLabel.setText("Deleted: " + node.getPath());
                rescanCurrentRoot();
            } catch (IOException ex) {
                var errorAlert = new Alert(Alert.AlertType.ERROR);
                errorAlert.setTitle("Delete Error");
                errorAlert.setHeaderText("Could not delete");
                errorAlert.setContentText(ex.getMessage());
                errorAlert.showAndWait();
                statusLabel.setText("Delete error: " + ex.getMessage());
            }
        }
    }

    private void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } else {
            Files.delete(path);
        }
    }

    private void chooseAndScan() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Select a folder to analyze");
        var dir = chooser.showDialog(stage);
        if (dir != null) {
            scan(dir.toPath());
        }
    }

    public void scan(Path rootPath) {
        scannedRootPath = rootPath;
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        cancelButton.setVisible(true);
        scanButton.setDisable(true);
        statusLabel.setText("Scanning: " + rootPath);
        treeView.setRoot(null);
        treemapChart.clear();

        scanner.scan(rootPath, progress -> Platform.runLater(() -> progressBar.setProgress(progress)))
                .thenAccept(root -> Platform.runLater(() -> {
                    if (root == null) {
                        statusLabel.setText("Scan cancelled");
                    } else {
                        currentRoot = root;
                        statusLabel.setText("Scan complete: " + rootPath);
                        sizeLabel.setText(formatSize(root.getSize()));
                        populateTree(root);
                        treemapChart.setRoot(root);
                    }
                    progressBar.setVisible(false);
                    cancelButton.setVisible(false);
                    scanButton.setDisable(false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("Error: " + ex.getMessage());
                        progressBar.setVisible(false);
                        cancelButton.setVisible(false);
                        scanButton.setDisable(false);
                    });
                    return null;
                });
    }

    private void rescanCurrentRoot() {
        if (scannedRootPath != null) {
            scan(scannedRootPath);
        }
    }

    private TreeView<FileNode> buildTreeView() {
        var tree = new TreeView<FileNode>();
        tree.setShowRoot(true);
        tree.setCellFactory(tv -> {
            var cell = new TreeCell<FileNode>() {
                @Override
                protected void updateItem(FileNode node, boolean empty) {
                    super.updateItem(node, empty);
                    if (empty || node == null) {
                        setText(null);
                        setGraphic(null);
                        setContextMenu(null);
                    } else {
                        setText(node.getName() + "  (" + formatSize(node.getSize()) + ")");
                        setContextMenu(buildContextMenu(node));
                    }
                }
            };
            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 1 && !cell.isEmpty()) {
                    treemapChart.setRoot(cell.getItem());
                }
            });
            return cell;
        });
        return tree;
    }

    private ContextMenu buildContextMenu(FileNode node) {
        var menu = new ContextMenu();
        var deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> deleteNode(node));
        menu.getItems().add(deleteItem);
        return menu;
    }

    private void populateTree(FileNode root) {
        var treeRoot = createTreeItem(root);
        treeRoot.setExpanded(true);
        treeView.setRoot(treeRoot);
    }

    private TreeItem<FileNode> createTreeItem(FileNode node) {
        var item = new TreeItem<>(node);
        for (var child : node.getChildren()) {
            if (child.isDirectory()) {
                item.getChildren().add(createTreeItem(child));
            }
        }
        return item;
    }

    private void onTreemapNodeClicked(FileNode node) {
        treemapChart.setRoot(node);
        highlightInTree(node);
    }

    private void highlightInTree(FileNode target) {
        if (treeView.getRoot() == null) return;
        expandAndSelect(treeView.getRoot(), target);
    }

    private boolean expandAndSelect(TreeItem<FileNode> item, FileNode target) {
        if (item.getValue() == target) {
            treeView.getSelectionModel().select(item);
            treeView.scrollTo(treeView.getSelectionModel().getSelectedIndex());
            return true;
        }
        item.setExpanded(true);
        for (var child : item.getChildren()) {
            if (expandAndSelect(child, target)) {
                return true;
            }
        }
        item.setExpanded(false);
        return false;
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        int unit = (int) (Math.log10(bytes) / Math.log10(1024));
        unit = Math.min(unit, SIZE_UNITS.length - 1);
        double value = bytes / Math.pow(1024, unit);
        return String.format("%.1f %s", value, SIZE_UNITS[unit]);
    }

    public void show() {
        stage.show();
    }
}
