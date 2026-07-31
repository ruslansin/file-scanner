package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import by.snql.filescanner.scanner.FileScanner;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MainWindow {

    private final Stage stage;
    private final FileScanner scanner;
    private final TreemapChart treemapChart;
    private final RingsChart ringsChart;
    private final StackPane chartStack;
    private final TreeView<FileNode> treeView;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private final Label sizeLabel;
    private final Label diskLabel;
    private final Label countLabel;
    private final ComboBox<String> sortCombo;
    private final Button scanButton;
    private final Button cancelButton;
    private final ToggleButton treemapBtn;
    private final ToggleButton ringsBtn;
    private final ToggleGroup viewToggle;
    private final Button homeButton;
    private final Button refreshButton;
    private final CheckBox hiddenFilesCheck;
    private final CheckBox darkModeCheck;
    private final Button settingsButton;
    private final TextField searchField;
    private final ComboBox<String> historyCombo;
    private final BottomTabs bottomTabs;

    private FileNode currentRoot;
    private Path scannedRootPath;
    private ChartView currentView = ChartView.TREEMAP;
    private final java.util.List<String> history = new ArrayList<>();
    private boolean updatingHistory;

    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB", "TB"};

    private enum ChartView { TREEMAP, RINGS }

    public MainWindow(Stage stage) {
        this.stage = stage;
        this.scanner = new FileScanner();
        this.treemapChart = new TreemapChart();
        this.ringsChart = new RingsChart();
        this.bottomTabs = new BottomTabs();

        chartStack = new StackPane(treemapChart, ringsChart);
        ringsChart.setVisible(false);

        treemapBtn = new ToggleButton("Treemap");
        ringsBtn = new ToggleButton("Rings");
        viewToggle = new ToggleGroup();
        treemapBtn.setToggleGroup(viewToggle);
        ringsBtn.setToggleGroup(viewToggle);
        treemapBtn.setSelected(true);
        viewToggle.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val == treemapBtn) switchView(ChartView.TREEMAP);
            else if (val == ringsBtn) switchView(ChartView.RINGS);
        });

        homeButton = new Button("Home");
        homeButton.setOnAction(e -> scan(Path.of(System.getProperty("user.home"))));

        refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> rescanCurrentRoot());

        hiddenFilesCheck = new CheckBox("Show hidden");
        hiddenFilesCheck.setSelected(Settings.get().scanHidden);
        scanner.setIncludeHidden(Settings.get().scanHidden);
        hiddenFilesCheck.setOnAction(e -> {
            Settings.get().scanHidden = hiddenFilesCheck.isSelected();
            Settings.get().save();
            scanner.setIncludeHidden(hiddenFilesCheck.isSelected());
            if (scannedRootPath != null) rescanCurrentRoot();
        });

        darkModeCheck = new CheckBox("Dark");
        darkModeCheck.setSelected(Settings.get().darkMode);
        darkModeCheck.setOnAction(e -> {
            Settings.get().darkMode = darkModeCheck.isSelected();
            Settings.get().save();
            applyTheme();
        });

        searchField = new TextField();
        searchField.setPromptText("Filter (name, glob*, regex:...)");
        searchField.setPrefWidth(200);
        searchField.textProperty().addListener((obs, old, val) -> applySearchFilter());

        historyCombo = new ComboBox<>();
        historyCombo.setPromptText("History");
        historyCombo.setPrefWidth(150);
        historyCombo.setOnAction(e -> {
            if (updatingHistory) return;
            var selected = historyCombo.getValue();
            if (selected != null && !selected.isEmpty()) {
                scan(Path.of(selected));
            }
        });

        sortCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Sort by Size", "Sort by Name", "Sort by Date"));
        sortCombo.setValue("Sort by " + capitalize(Settings.get().defaultSort));
        sortCombo.setPrefWidth(130);
        sortCombo.setOnAction(e -> {
            Settings.get().defaultSort = sortCombo.getValue()
                    .replace("Sort by ", "").toLowerCase();
            Settings.get().save();
            if (currentRoot != null) {
                applySort(currentRoot);
                populateTree(currentRoot);
            }
        });

        settingsButton = new Button("⚙");
        settingsButton.setStyle("-fx-font-size: 14px; -fx-padding: 4 10;");
        settingsButton.setOnAction(e -> showSettingsDialog());

        treeView = buildTreeView();
        progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        statusLabel = new Label("Select a folder to scan");
        sizeLabel = new Label("");
        diskLabel = new Label("");
        countLabel = new Label("");
        scanButton = new Button("Scan Folder");
        cancelButton = new Button("Cancel");
        cancelButton.setVisible(false);

        scanButton.setOnAction(e -> chooseAndScan());
        cancelButton.setOnAction(e -> scanner.cancel());
        treemapChart.setOnNodeClicked(this::onChartNodeClicked);
        ringsChart.setOnNodeClicked(this::onChartNodeClicked);

        var toolbar = new HBox(6, scanButton, cancelButton, homeButton, refreshButton,
                new Separator(), hiddenFilesCheck, darkModeCheck,
                new Separator(), sortCombo,
                new Separator(), treemapBtn, ringsBtn,
                new Separator(), searchField, historyCombo, settingsButton, progressBar);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        var statusBar = new HBox(10, statusLabel, countLabel, sizeLabel, diskLabel);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.setStyle("-fx-background-color: #f0f0f0;");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        treeView.setMinWidth(250);
        treeView.setPrefWidth(300);

        var mainSplit = new SplitPane();
        mainSplit.getItems().add(treeView);
        mainSplit.getItems().add(chartStack);
        mainSplit.setDividerPositions(0.3);

        var bottomPane = bottomTabs.getPane();
        bottomPane.setMaxHeight(200);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        var root = new VBox(toolbar, mainSplit, bottomPane, statusBar);
        VBox.setVgrow(root, Priority.ALWAYS);

        var scene = new Scene(root, 1100, 750);
        scene.getStylesheets().add(getClass().getResource("/styles/main.css").toExternalForm());
        scene.addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        setupDragAndDrop(scene);

        stage.setTitle("File Scanner — Disk Space Analyzer");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        applyTheme();
    }

    private void setupDragAndDrop(Scene scene) {
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.LINK);
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            if (!files.isEmpty()) {
                var path = files.get(0).toPath();
                if (Files.isDirectory(path)) scan(path);
            }
            e.setDropCompleted(true);
            e.consume();
        });
    }

    private void switchView(ChartView view) {
        currentView = view;
        treemapChart.setVisible(view == ChartView.TREEMAP);
        ringsChart.setVisible(view == ChartView.RINGS);
        if (currentRoot != null) {
            treemapChart.setRoot(currentRoot);
            ringsChart.setRoot(currentRoot);
        }
    }

    private void onChartNodeClicked(FileNode node) {
        if (currentView == ChartView.TREEMAP) treemapChart.setRoot(node);
        else ringsChart.setRoot(node);
        highlightInTree(node);
    }

    private void onKeyPressed(KeyEvent e) {
        if (e.getCode() == KeyCode.DELETE) {
            var selected = treeView.getSelectionModel().getSelectedItems();
            if (selected != null && !selected.isEmpty()) {
                var nodes = selected.stream()
                        .filter(item -> item.getValue() != null)
                        .map(TreeItem::getValue)
                        .toList();
                if (!nodes.isEmpty()) deleteNodes(nodes);
            }
        }
        if (e.isControlDown() && e.getCode() == KeyCode.F) {
            searchField.requestFocus();
        }
    }

    private void applySearchFilter() {
        var text = searchField.getText().trim();
        if (text.isEmpty()) {
            if (currentRoot != null) {
                populateTree(currentRoot);
                treemapChart.setRoot(currentRoot);
                ringsChart.setRoot(currentRoot);
            }
            return;
        }
        if (currentRoot == null) return;
        var filtered = filterTree(currentRoot, text);
        if (filtered != null) {
            populateTree(filtered);
            treemapChart.setRoot(filtered);
            ringsChart.setRoot(filtered);
        }
    }

    private boolean matchesFilter(String name, String query) {
        var n = name.toLowerCase();
        if (query.startsWith("regex:")) {
            try { return n.matches(".*" + query.substring(6) + ".*"); } catch (Exception e) { return n.contains(query.substring(6)); }
        }
        if (query.contains("*") || query.contains("?")) {
            return globMatch(n, query);
        }
        return n.contains(query.toLowerCase());
    }

    private static boolean globMatch(String name, String glob) {
        String regex = globToRegex(glob);
        return name.matches(regex);
    }

    private static String globToRegex(String glob) {
        var sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append('.'); break;
                case '.': sb.append("\\."); break;
                default:  sb.append(Character.toLowerCase(c));
            }
        }
        sb.append("$");
        return sb.toString();
    }

    private FileNode filterTree(FileNode node, String query) {
        if (matchesFilter(node.getName(), query)) return node;

        var filteredChildren = new java.util.ArrayList<FileNode>();
        for (var child : node.getChildren()) {
            var filtered = filterTree(child, query);
            if (filtered != null) filteredChildren.add(filtered);
        }

        if (!filteredChildren.isEmpty()) {
            var copy = new FileNode(node.getPath(), node.getName(), node.isDirectory(), 0);
            for (var child : filteredChildren) copy.addChild(child);
            return copy;
        }
        return null;
    }

    private void deleteNodes(java.util.List<FileNode> nodes) {
        if (nodes.isEmpty()) return;
        if (nodes.size() == 1) {
            deleteSingle(nodes.get(0));
            return;
        }

        long totalSize = nodes.stream().mapToLong(FileNode::getSize).sum();
        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Deletion");
        alert.setHeaderText("Delete " + nodes.size() + " items?");
        alert.setContentText("Total size: " + formatSize(totalSize));

        if (alert.showAndWait().orElse(null) == ButtonType.OK) {
            for (var node : nodes) {
                if (node.getPath() == null) continue;
                try { deleteRecursive(node.getPath()); } catch (IOException ignored) {}
            }
            statusLabel.setText("Deleted " + nodes.size() + " items");
            rescanCurrentRoot();
        }
    }

    private void deleteSingle(FileNode node) {
        if (node.getPath() == null) return;

        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Deletion");
        alert.setHeaderText("Delete " + (node.isDirectory() ? "folder" : "file") + "?");
        alert.setContentText(node.getPath().toString() + "\n(" + formatSize(node.getSize()) + ")");

        if (alert.showAndWait().orElse(null) == ButtonType.OK) {
            try {
                deleteRecursive(node.getPath());
                statusLabel.setText("Deleted: " + node.getPath());
                rescanCurrentRoot();
            } catch (IOException ex) {
                showError("Delete Error", "Could not delete", ex.getMessage());
            }
        }
    }

    private void deleteNode(FileNode node) {
        deleteNodes(java.util.List.of(node));
    }

    private void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } else {
            Files.delete(path);
        }
    }

    private void chooseAndScan() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Select a folder to analyze");
        var dir = chooser.showDialog(stage);
        if (dir != null) scan(dir.toPath());
    }

    public void scan(Path rootPath) {
        addToHistory(rootPath);
        scannedRootPath = rootPath;
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        cancelButton.setVisible(true);
        scanButton.setDisable(true);
        statusLabel.setText("Scanning: " + rootPath);
        treeView.setRoot(null);
        treemapChart.clear();
        ringsChart.clear();
        bottomTabs.setRoot(null);

        var lastUpdate = new long[]{0};
        scanner.scan(rootPath, p -> maybeUpdate(() -> progressBar.setProgress(p), lastUpdate),
                        sp -> maybeUpdate(() -> statusLabel.setText(String.format(
                                "Scanning: %s (%s found, %s)",
                                rootPath, sp.filesDiscovered(), formatSize(sp.totalSizeSoFar()))), lastUpdate))
                .thenAccept(root -> Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    if (root == null) {
                        statusLabel.setText("Scan cancelled");
                    } else {
                        currentRoot = root;
                        applySort(root);
                        statusLabel.setText("Scan complete: " + rootPath);
                        sizeLabel.setText(formatSize(root.getSize()));
                        updateCounts(root);
                        updateDiskInfo(rootPath);
                        populateTree(root);
                        treemapChart.setRoot(root);
                        ringsChart.setRoot(root);
                        bottomTabs.setRoot(root);
                        Settings.get().lastScannedPath = rootPath.toString();
                        Settings.get().save();
                    }
                    progressBar.setVisible(false);
                    cancelButton.setVisible(false);
                    scanButton.setDisable(false);
                    searchField.clear();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        showError("Scan Error", "Scan failed", ex.getMessage());
                        progressBar.setVisible(false);
                        cancelButton.setVisible(false);
                        scanButton.setDisable(false);
                    });
                    return null;
                });
    }

    private void addToHistory(Path path) {
        if (updatingHistory) return;
        updatingHistory = true;
        var s = path.toString();
        history.remove(s);
        history.add(0, s);
        if (history.size() > 20) history.remove(history.size() - 1);
        historyCombo.setItems(FXCollections.observableArrayList(history));
        historyCombo.setValue(s);
        updatingHistory = false;
    }

    private void rescanCurrentRoot() {
        if (scannedRootPath != null) scan(scannedRootPath);
    }

    private TreeView<FileNode> buildTreeView() {
        var tree = new TreeView<FileNode>();
        tree.setShowRoot(true);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(tv -> {
            var cell = new TreeCell<FileNode>() {
                private final Tooltip ttip = new Tooltip();
                @Override
                protected void updateItem(FileNode node, boolean empty) {
                    super.updateItem(node, empty);
                    if (empty || node == null) {
                        setText(null); setGraphic(null); setContextMenu(null); setTooltip(null);
                        setStyle("");
                    } else {
                        var prefix = new java.lang.StringBuilder();
                        if (node.isBuildArtifact()) prefix.append("🧹 ");
                        if (node.isSymlink()) prefix.append("↗ ");
                        else if (node.isHardlinkReference()) prefix.append("⫘ ");
                        setText(prefix + node.getName() + "  (" + formatSize(node.getSize()) + ")");
                        setContextMenu(buildContextMenu(node));
                        ttip.setText(node.getPath() + "\n" + formatSize(node.getSize()));
                        setTooltip(ttip);
                        if (node.isBuildArtifact()) {
                            setStyle("-fx-text-fill: #e67e22; -fx-font-weight: bold;");
                        } else {
                            setStyle("");
                        }
                    }
                }
            };
            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 1 && !cell.isEmpty()) {
                    treemapChart.setRoot(cell.getItem());
                    ringsChart.setRoot(cell.getItem());
                }
            });
            return cell;
        });
        return tree;
    }

    private ContextMenu buildContextMenu(FileNode node) {
        var menu = new ContextMenu();

        var openItem = new MenuItem("Open in File Manager");
        openItem.setOnAction(e -> openInFileManager(node.getPath()));
        menu.getItems().add(openItem);

        if (!node.isDirectory()) {
            var openFileItem = new MenuItem("Open File");
            openFileItem.setOnAction(e -> openFile(node.getPath()));
            menu.getItems().add(openFileItem);
        }

        var exportMenu = new Menu("Export");
        for (var fmt : new String[]{"CSV", "JSON", "HTML"}) {
            var item = new MenuItem(fmt);
            item.setOnAction(e -> exportReport(fmt));
            exportMenu.getItems().add(item);
        }
        menu.getItems().add(exportMenu);

        menu.getItems().add(new SeparatorMenuItem());

        if (node.isBuildArtifact()) {
            var deleteBuildItem = new MenuItem("Delete Build Artifact");
            deleteBuildItem.setOnAction(e -> {
                try {
                    deleteRecursive(node.getPath());
                    statusLabel.setText("Deleted: " + node.getPath());
                    rescanCurrentRoot();
                } catch (IOException ex) {
                    showError("Delete Error", "Could not delete", ex.getMessage());
                }
            });
            menu.getItems().add(deleteBuildItem);
            menu.getItems().add(new SeparatorMenuItem());
        }

        var deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> deleteNode(node));
        menu.getItems().add(deleteItem);

        return menu;
    }

    private void exportReport(String format) {
        if (currentRoot == null) return;
        var chooser = new FileChooser();
        chooser.setTitle("Export " + format + " Report");
        chooser.setInitialFileName("file-scanner-report." + format.toLowerCase());
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(format + " files", "*." + format.toLowerCase()));
        var file = chooser.showSaveDialog(stage);
        if (file != null) {
            try {
                ExportUtils.export(currentRoot, file.toPath(), format.toLowerCase());
                statusLabel.setText("Exported to " + file);
            } catch (IOException ex) {
                showError("Export Error", "Could not export", ex.getMessage());
            }
        }
    }

    private void openInFileManager(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(Files.isDirectory(path) ? path.toFile() : path.getParent().toFile());
            }
        } catch (IOException ex) {
            statusLabel.setText("Cannot open: " + ex.getMessage());
        }
    }

    private void openFile(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (IOException ex) {
            statusLabel.setText("Cannot open file: " + ex.getMessage());
        }
    }

    private void updateCounts(FileNode root) {
        var files = FileAnalysis.flattenFiles(root);
        long fileCount = files.size();
        long dirCount = countDirs(root);
        countLabel.setText(fileCount + " files, " + dirCount + " dirs");
    }

    private long countDirs(FileNode node) {
        long count = node.isDirectory() ? 1 : 0;
        for (var child : node.getChildren()) count += countDirs(child);
        return count;
    }

    private void updateDiskInfo(Path rootPath) {
        try {
            var store = Files.getFileStore(rootPath);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            diskLabel.setText(formatSize(free) + " free of " + formatSize(total));
        } catch (IOException e) {
            diskLabel.setText("");
        }
    }

    private void applySort(FileNode root) {
        var selected = sortCombo.getValue();
        if (root == null) return;

        Comparator<FileNode> comp = switch (selected) {
            case "Sort by Name" -> Comparator.comparing(FileNode::getName, String.CASE_INSENSITIVE_ORDER);
            case "Sort by Date" -> Comparator.comparingLong(
                    f -> { try { return Files.readAttributes(f.getPath(), BasicFileAttributes.class).lastModifiedTime().toMillis(); } catch (IOException e) { return 0; } });
            default -> Comparator.comparingLong(FileNode::getSize).reversed();
        };
        sortRecursive(root, comp);
    }

    private void sortRecursive(FileNode node, Comparator<FileNode> comp) {
        node.getChildren().sort(comp);
        for (var child : node.getChildren()) sortRecursive(child, comp);
    }

    private void populateTree(FileNode root) {
        var treeRoot = createLazyTreeItem(root);
        treeRoot.setExpanded(true);
        treeView.setRoot(treeRoot);
    }

    private TreeItem<FileNode> createLazyTreeItem(FileNode node) {
        var item = new TreeItem<>(node);
        if (node.isDirectory() && !node.getChildren().isEmpty()) {
            item.getChildren().add(new TreeItem<>(new FileNode(null, "Loading...", false, 0)));
            item.expandedProperty().addListener((obs, old, val) -> {
                if (val && item.getChildren().size() == 1 &&
                        "Loading...".equals(item.getChildren().get(0).getValue().getName())) {
                    item.getChildren().clear();
                    for (var child : node.getChildren()) {
                        if (child.isDirectory()) {
                            item.getChildren().add(createLazyTreeItem(child));
                        }
                    }
                }
            });
        }
        return item;
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
            if (expandAndSelect(child, target)) return true;
        }
        item.setExpanded(false);
        return false;
    }

    private void applyTheme() {
        var root = stage.getScene().getRoot();
        if (darkModeCheck.isSelected()) {
            root.setStyle("-fx-base: #2b2b2b; -fx-background: #3c3f41; -fx-control-inner-background: #3c3f41; -fx-text-fill: #bbbbbb;");
        } else {
            root.setStyle("");
        }
    }

    private void showError(String title, String header, String msg) {
        var alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(msg != null ? msg : "");
        alert.showAndWait();
        statusLabel.setText(header + ": " + (msg != null ? msg : ""));
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        int unit = (int) (Math.log10(bytes) / Math.log10(1024));
        unit = Math.min(unit, SIZE_UNITS.length - 1);
        double value = bytes / Math.pow(1024, unit);
        return String.format("%.1f %s", value, SIZE_UNITS[unit]);
    }

    private void showSettingsDialog() {
        var s = Settings.get();
        var shaCb = new CheckBox("SHA-256 duplicate detection (slow, exact)");
        shaCb.setSelected(s.duplicateSHA256);

        var hiddenCb = new CheckBox("Show hidden files on startup");
        hiddenCb.setSelected(s.scanHidden);

        var darkCb = new CheckBox("Dark mode on startup");
        darkCb.setSelected(s.darkMode);

        var sortCb = new ComboBox<String>(FXCollections.observableArrayList("size", "name", "date"));
        sortCb.setValue(s.defaultSort);

        var form = new javafx.scene.layout.GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(15));
        form.add(new Label("Duplicates:"), 0, 0);
        form.add(shaCb, 1, 0);
        form.add(new Label("Scanning:"), 0, 1);
        form.add(hiddenCb, 1, 1);
        form.add(new Label("Appearance:"), 0, 2);
        form.add(darkCb, 1, 2);
        form.add(new Label("Default sort:"), 0, 3);
        form.add(sortCb, 1, 3);

        var rootsLabel = new Label("Scan roots for projects (one per line):");
        form.add(rootsLabel, 0, 4);
        var rootsArea = new TextArea(String.join("\n", s.scanRoots));
        rootsArea.setPrefRowCount(4);
        rootsArea.setPrefWidth(350);
        form.add(rootsArea, 1, 4);

        var projectEnabledCb = new CheckBox("Enable project scanning");
        projectEnabledCb.setSelected(s.projectScanEnabled);
        form.add(projectEnabledCb, 0, 5, 2, 1);

        var depthLabel = new Label("Max scan depth:");
        form.add(depthLabel, 0, 6);
        var depthField = new TextField(String.valueOf(s.projectScanDepth));
        depthField.setPrefWidth(60);
        form.add(depthField, 1, 6);

        var note = new Label("Duplicate detection uses SHA-256. Disabled by default (slow on large scans).");
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.add(note, 0, 7, 2, 1);

        var dialog = new javafx.scene.control.Dialog<ButtonType>();
        dialog.setTitle("Settings");
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            s.duplicateSHA256 = shaCb.isSelected();
            s.scanHidden = hiddenCb.isSelected();
            s.darkMode = darkCb.isSelected();
            s.defaultSort = sortCb.getValue();
            s.scanRoots = List.of(rootsArea.getText().split("\\n"))
                    .stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
            s.projectScanEnabled = projectEnabledCb.isSelected();
            try { s.projectScanDepth = Integer.parseInt(depthField.getText()); } catch (NumberFormatException e) {}
            s.save();

            hiddenFilesCheck.setSelected(s.scanHidden);
            scanner.setIncludeHidden(s.scanHidden);
            darkModeCheck.setSelected(s.darkMode);
            applyTheme();
            sortCombo.setValue("Sort by " + capitalize(s.defaultSort));
            if (currentRoot != null) rescanCurrentRoot();
        }
    }

    private static void maybeUpdate(Runnable action, long[] lastUpdate) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate[0] >= 200) {
            lastUpdate[0] = now;
            Platform.runLater(action);
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public void show() {
        stage.show();
        var lastPath = Settings.get().lastScannedPath;
        if (lastPath != null && !lastPath.isEmpty()) {
            var path = Path.of(lastPath);
            if (Files.isDirectory(path)) {
                scan(path);
            }
        }
    }
}
