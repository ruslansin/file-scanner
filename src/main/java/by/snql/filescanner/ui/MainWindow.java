package by.snql.filescanner.ui;

import by.snql.filescanner.config.Settings;
import by.snql.filescanner.config.CacheManager;
import by.snql.filescanner.core.cleanup.DeletionService;
import by.snql.filescanner.core.export.ExportUtils;
import by.snql.filescanner.core.export.PdfReport;
import by.snql.filescanner.core.util.SizeFormat;
import by.snql.filescanner.model.FileNode;
import by.snql.filescanner.scanner.FileScanner;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MainWindow {

    private static final String DEFAULT_TITLE = "File Scanner \u2014 Disk Space Analyzer";

    private final Stage stage;
    private final FileScanner scanner;
    private ScheduledExecutorService memoryMonitor;
    private final TreemapChart treemapChart;
    private final RingsChart ringsChart;
    private final StackPane chartStack;
    private final HBox breadcrumbBar;
    private final Button upButton;
    private final TreeView<FileNode> treeView;
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
    private final List<String> history = new ArrayList<>();
    private boolean updatingHistory;

    /** Drill-down stack for the treemap/rings views: index 0 is the scan root. */
    private final List<FileNode> viewStack = new ArrayList<>();

    private enum ChartView { TREEMAP, RINGS }

    public MainWindow(Stage stage) {
        this.stage = stage;
        this.scanner = new FileScanner();
        this.treemapChart = new TreemapChart();
        this.ringsChart = new RingsChart();
        this.bottomTabs = new BottomTabs();

        applyScanSettings();

        chartStack = new StackPane(treemapChart, ringsChart);
        ringsChart.setVisible(false);

        upButton = new Button("\u2191 Up");
        upButton.setDisable(true);
        upButton.setOnAction(e -> navigateUp());
        breadcrumbBar = new HBox(6);
        breadcrumbBar.setPadding(new Insets(4, 8, 4, 8));
        breadcrumbBar.setAlignment(Pos.CENTER_LEFT);

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
        hiddenFilesCheck.setOnAction(e -> {
            Settings.get().scanHidden = hiddenFilesCheck.isSelected();
            Settings.get().save();
            applyScanSettings();
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

        history.addAll(Settings.get().recentPaths);
        historyCombo = new ComboBox<>(FXCollections.observableArrayList(history));
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
                    .replace("Sort by ", "").toLowerCase(Locale.ROOT);
            Settings.get().save();
            if (currentRoot != null) {
                applySort(currentRoot);
                populateTree(currentRoot);
            }
        });

        settingsButton = new Button("\u2699");
        settingsButton.setStyle("-fx-font-size: 14px; -fx-padding: 4 10;");
        settingsButton.setOnAction(e -> showSettingsDialog());

        treeView = buildTreeView();
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

        bottomTabs.setOnStatus(statusLabel::setText);

        var toolbar = new HBox(6, scanButton, cancelButton, homeButton, refreshButton,
                new Separator(), hiddenFilesCheck, darkModeCheck,
                new Separator(), sortCombo,
                new Separator(), treemapBtn, ringsBtn,
                new Separator(), searchField, historyCombo, settingsButton);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        var statusBar = new HBox(10, statusLabel, countLabel, sizeLabel, diskLabel);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.getStyleClass().add("status-bar");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        treeView.setMinWidth(250);
        treeView.setPrefWidth(300);

        var chartPane = new VBox(new HBox(6, upButton, breadcrumbBar), chartStack);
        VBox.setVgrow(chartStack, Priority.ALWAYS);

        var mainSplit = new SplitPane();
        mainSplit.getItems().add(treeView);
        mainSplit.getItems().add(chartPane);
        mainSplit.setDividerPositions(0.3);
        // Give the tree+charts a sane minimum so dragging the outer divider all the way
        // down doesn't collapse them to nothing.
        mainSplit.setMinHeight(150);

        var bottomPane = bottomTabs.getPane();
        // No more fixed 200px cap — the outer vertical SplitPane's divider below lets the
        // user drag this panel taller or shorter themselves, which a fixed max height
        // didn't allow no matter how much the window was maximized.
        bottomPane.setMinHeight(80);

        var outerSplit = new SplitPane();
        outerSplit.setOrientation(Orientation.VERTICAL);
        outerSplit.getItems().addAll(mainSplit, bottomPane);
        outerSplit.setDividerPositions(0.72);
        VBox.setVgrow(outerSplit, Priority.ALWAYS);

        var root = new VBox(toolbar, outerSplit, statusBar);

        var scene = new Scene(root, 1100, 750);
        scene.getStylesheets().add(getClass().getResource("/styles/main.css").toExternalForm());
        scene.addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        setupDragAndDrop(scene);

        stage.setTitle(DEFAULT_TITLE);
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        applyTheme();
    }

    private void applyScanSettings() {
        scanner.setIncludeHidden(Settings.get().scanHidden);
        scanner.setDetectBuildArtifacts(Settings.get().projectScanEnabled);
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
        renderCurrentView();
    }

    // ── Treemap/Rings drill-down navigation ─────────────────────────────

    private void resetView(FileNode node) {
        viewStack.clear();
        if (node != null) viewStack.add(node);
        renderCurrentView();
    }

    private void pushView(FileNode node) {
        viewStack.add(node);
        renderCurrentView();
    }

    private void navigateUp() {
        if (viewStack.size() > 1) {
            viewStack.remove(viewStack.size() - 1);
            renderCurrentView();
        }
    }

    private void navigateTo(int index) {
        if (index < 0 || index >= viewStack.size()) return;
        while (viewStack.size() > index + 1) viewStack.remove(viewStack.size() - 1);
        renderCurrentView();
    }

    private void renderCurrentView() {
        if (viewStack.isEmpty()) {
            upButton.setDisable(true);
            breadcrumbBar.getChildren().clear();
            return;
        }
        var top = viewStack.get(viewStack.size() - 1);
        treemapChart.setRoot(top);
        ringsChart.setRoot(top);
        upButton.setDisable(viewStack.size() <= 1);
        rebuildBreadcrumb();
    }

    private void rebuildBreadcrumb() {
        breadcrumbBar.getChildren().clear();
        for (int i = 0; i < viewStack.size(); i++) {
            final int idx = i;
            var name = viewStack.get(i).getName();
            var link = new Hyperlink(name.isEmpty() ? "/" : name);
            link.setOnAction(e -> navigateTo(idx));
            breadcrumbBar.getChildren().add(link);
            if (i < viewStack.size() - 1) {
                breadcrumbBar.getChildren().add(new Label("\u203a"));
            }
        }
    }

    private void onChartNodeClicked(FileNode node) {
        pushView(node);
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
                resetView(currentRoot);
            }
            return;
        }
        if (currentRoot == null) return;
        var filtered = filterTree(currentRoot, text);
        if (filtered != null) {
            populateTree(filtered);
            resetView(filtered);
        }
    }

    private boolean matchesFilter(String name, String query) {
        var n = name.toLowerCase(Locale.ROOT);
        if (query.startsWith("regex:")) {
            try { return n.matches(".*" + query.substring(6) + ".*"); } catch (Exception e) { return n.contains(query.substring(6)); }
        }
        if (query.contains("*") || query.contains("?")) {
            return globMatch(n, query);
        }
        return n.contains(query.toLowerCase(Locale.ROOT));
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
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.' -> sb.append("\\.");
                default -> sb.append(Character.toLowerCase(c));
            }
        }
        sb.append("$");
        return sb.toString();
    }

    private FileNode filterTree(FileNode node, String query) {
        if (matchesFilter(node.getName(), query)) return node;

        var filteredChildren = new ArrayList<FileNode>();
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

    private void deleteNodes(List<FileNode> nodes) {
        if (nodes.isEmpty()) return;

        long totalSize = nodes.stream().mapToLong(FileNode::getSize).sum();
        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Deletion");
        alert.setHeaderText("Delete " + nodes.size() + " item(s)?");
        alert.setContentText("Total size: " + SizeFormat.format(totalSize) +
                (Settings.get().moveToTrash ? "\n(moved to trash)" : "\n(PERMANENTLY deleted)"));

        if (alert.showAndWait().orElse(null) != ButtonType.OK) return;

        var paths = nodes.stream().map(FileNode::getPath).filter(p -> p != null).toList();
        var result = DeletionService.delete(paths, Settings.get().moveToTrash);
        reportDeletionResult(result);

        if (result.deleted().isEmpty() || currentRoot == null) return;

        var removedPaths = new HashSet<>(result.deleted());
        removeDeletedPaths(currentRoot, removedPaths);

        applySort(currentRoot);
        populateTree(currentRoot);
        resetView(currentRoot);
        sizeLabel.setText(SizeFormat.format(currentRoot.getSize()));
        updateCounts(currentRoot);
        bottomTabs.setRoot(currentRoot);
        Thread.ofVirtual().start(() -> CacheManager.saveLastScan(currentRoot));
    }

    private static boolean removeDeletedPaths(FileNode parent, Set<Path> removedPaths) {
        var toRemove = new ArrayList<FileNode>();
        for (var child : parent.getChildren()) {
            if (removedPaths.contains(child.getPath())) {
                toRemove.add(child);
            }
        }
        for (var child : toRemove) {
            parent.removeChild(child);
        }
        for (var child : parent.getChildren()) {
            if (child.isDirectory() && removeDeletedPaths(child, removedPaths)) {
                if (child.getChildren().isEmpty()) {
                    child.setSize(0);
                }
            }
        }
        return !toRemove.isEmpty();
    }

    private void reportDeletionResult(DeletionService.DeletionResult result) {
        if (result.allSucceeded()) {
            statusLabel.setText("Deleted " + result.deleted().size() + " item(s)");
            return;
        }
        statusLabel.setText("Deleted " + result.deleted().size() + ", failed " + result.failureCount());
        var alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Some items were not deleted");
        alert.setHeaderText(result.deleted().size() + " deleted, " + result.failureCount() + " failed");
        var details = result.errors().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        alert.setContentText(details);
        alert.showAndWait();
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
        cancelButton.setVisible(true);
        scanButton.setDisable(true);
        statusLabel.setText("Scanning: " + rootPath);
        treeView.setRoot(null);
        treemapChart.clear();
        ringsChart.clear();
        viewStack.clear();
        bottomTabs.setRoot(null);

        startMemoryMonitor();

        scanner.scan(rootPath, null,
                        partial -> Platform.runLater(() -> {
                            partial.sortChildren();
                            updateTreeLive(partial);
                            if (viewStack.isEmpty()) resetView(partial); else viewStack.set(0, partial);
                            renderCurrentView();
                            sizeLabel.setText(SizeFormat.format(partial.getSize()));
                        }))
                .thenAccept(root -> Platform.runLater(() -> {
                    stopMemoryMonitor();
                    if (root == null) {
                        statusLabel.setText("Scan cancelled");
                    } else {
                        currentRoot = root;
                        applySort(root);
                        statusLabel.setText("Scan complete: " + rootPath);
                        sizeLabel.setText(SizeFormat.format(root.getSize()));
                        updateCounts(root);
                        updateDiskInfo(rootPath);
                        updateTreeLive(root);
                        resetView(root);
                        bottomTabs.setRoot(root);
                        Settings.get().lastScannedPath = rootPath.toString();
                        Settings.get().save();
                        Thread.ofVirtual().start(() -> CacheManager.saveLastScan(root));
                    }
                    cancelButton.setVisible(false);
                    scanButton.setDisable(false);
                    searchField.clear();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        stopMemoryMonitor();
                        showError("Scan Error", "Scan failed", ex.getMessage());
                        cancelButton.setVisible(false);
                        scanButton.setDisable(false);
                    });
                    return null;
                });
    }

    private void startMemoryMonitor() {
        stopMemoryMonitor();
        memoryMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "memory-monitor");
            t.setDaemon(true);
            return t;
        });
        memoryMonitor.scheduleAtFixedRate(() -> {
            var title = formatMemoryTitle();
            Platform.runLater(() -> stage.setTitle(title));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopMemoryMonitor() {
        if (memoryMonitor != null) {
            memoryMonitor.shutdownNow();
            memoryMonitor = null;
        }
        stage.setTitle(DEFAULT_TITLE);
    }

    private static String formatMemoryTitle() {
        var rt = Runtime.getRuntime();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        long maxHeap = rt.maxMemory();

        var memBean = ManagementFactory.getMemoryMXBean();
        var nonHeap = memBean.getNonHeapMemoryUsage();
        long usedMeta = nonHeap.getUsed();
        long maxMeta = nonHeap.getMax();

        return "File Scanner (heap: " + SizeFormat.format(usedHeap) + "/" + SizeFormat.format(maxHeap)
                + " meta: " + SizeFormat.format(usedMeta)
                + (maxMeta >= 0 ? "/" + SizeFormat.format(maxMeta) : "")
                + ") \u2014 Disk Space Analyzer";
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

        Settings.get().recentPaths = new ArrayList<>(history);
        Settings.get().save();
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
                        var prefix = new StringBuilder();
                        if (node.isBuildArtifact()) prefix.append("\uD83E\uDDF9 ");
                        if (node.isSymlink()) prefix.append("\u2197 ");
                        else if (node.isHardlinkReference()) prefix.append("\u22D8 ");
                        setText(prefix + node.getName() + "  (" + SizeFormat.format(node.getSize()) + ")");
                        setContextMenu(buildContextMenu(node));
                        ttip.setText(node.getPath() + "\n" + SizeFormat.format(node.getSize()));
                        setTooltip(ttip);
                        setStyle(node.isBuildArtifact() ? "-fx-text-fill: #e67e22; -fx-font-weight: bold;" : "");
                    }
                }
            };
            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 1 && !cell.isEmpty()) {
                    resetView(cell.getItem());
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
        for (var fmt : new String[]{"CSV", "JSON", "HTML", "PDF"}) {
            var item = new MenuItem(fmt);
            item.setOnAction(e -> exportReport(fmt));
            exportMenu.getItems().add(item);
        }
        menu.getItems().add(exportMenu);

        menu.getItems().add(new SeparatorMenuItem());

        if (node.isBuildArtifact()) {
            var deleteBuildItem = new MenuItem("Delete Build Artifact");
            deleteBuildItem.setOnAction(e -> deleteNodes(collectSelectedNodes(node)));
            menu.getItems().add(deleteBuildItem);
            menu.getItems().add(new SeparatorMenuItem());
        }

        var deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> deleteNodes(collectSelectedNodes(node)));
        menu.getItems().add(deleteItem);

        return menu;
    }

    private List<FileNode> collectSelectedNodes(FileNode rightClicked) {
        var selected = treeView.getSelectionModel().getSelectedItems().stream()
                .filter(item -> item.getValue() != null)
                .map(TreeItem::getValue)
                .toList();
        if (selected.size() > 1 && selected.contains(rightClicked)) {
            return selected;
        }
        return List.of(rightClicked);
    }

    private void exportReport(String format) {
        if (currentRoot == null) return;
        var chooser = new FileChooser();
        chooser.setTitle("Export " + format + " Report");
        chooser.setInitialFileName("file-scanner-report." + format.toLowerCase(Locale.ROOT));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(format + " files", "*." + format.toLowerCase(Locale.ROOT)));
        var file = chooser.showSaveDialog(stage);
        if (file == null) return;

        var root = currentRoot;
        statusLabel.setText("Exporting " + format + "...");
        Thread.ofVirtual().start(() -> {
            try {
                if ("PDF".equalsIgnoreCase(format)) {
                    PdfReport.generate(root, file.toPath(), 100);
                } else {
                    ExportUtils.export(root, file.toPath(), format.toLowerCase(Locale.ROOT));
                }
                Platform.runLater(() -> statusLabel.setText("Exported to " + file));
            } catch (IOException ex) {
                Platform.runLater(() -> showError("Export Error", "Could not export", ex.getMessage()));
            }
        });
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
        var files = by.snql.filescanner.core.analysis.FileAnalysis.flattenFiles(root);
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
            diskLabel.setText(SizeFormat.format(free) + " free of " + SizeFormat.format(total));
        } catch (IOException e) {
            diskLabel.setText("");
        }
    }

    private void applySort(FileNode root) {
        var selected = sortCombo.getValue();
        if (root == null) return;

        Comparator<FileNode> comp = switch (selected) {
            case "Sort by Name" -> Comparator.comparing(FileNode::getName, String.CASE_INSENSITIVE_ORDER);
            case "Sort by Date" -> Comparator.comparingLong(FileNode::getLastModified);
            default -> Comparator.comparingLong(FileNode::getSize).reversed();
        };
        sortRecursive(root, comp);
    }

    private void sortRecursive(FileNode node, Comparator<FileNode> comp) {
        node.getChildren().sort(comp);
        for (var child : node.getChildren()) sortRecursive(child, comp);
    }

    private void populateTree(FileNode root) {
        treeView.setRoot(null);
        updateTreeLive(root);
    }

    private void updateTreeLive(FileNode root) {
        if (treeView.getRoot() == null) {
            var treeRoot = new TreeItem<>(root);
            treeRoot.setExpanded(true);
            mergeChildren(treeRoot, root);
            treeView.setRoot(treeRoot);
        } else {
            treeView.getRoot().setValue(root);
            mergeChildren(treeView.getRoot(), root);
        }
    }

    /**
     * Merges live-scan updates into the existing {@link TreeItem} structure in place
     * (so JavaFX doesn't lose expansion/selection state), matching by path rather than
     * object identity, including both files and directories, and removing entries that
     * no longer exist in the fresh tree.
     */
    private void mergeChildren(TreeItem<FileNode> item, FileNode fresh) {
        var existing = new java.util.LinkedHashMap<Path, TreeItem<FileNode>>();
        for (var child : item.getChildren()) {
            if (child.getValue() != null && child.getValue().getPath() != null) {
                existing.put(child.getValue().getPath(), child);
            }
        }

        item.getChildren().clear();
        for (var freshChild : fresh.getChildren()) {
            var ex = existing.get(freshChild.getPath());
            if (ex != null) {
                ex.setValue(freshChild);
                if (freshChild.isDirectory()) mergeChildren(ex, freshChild);
                item.getChildren().add(ex);
            } else {
                item.getChildren().add(buildTreeItem(freshChild));
            }
        }
    }

    /** Recursively builds a full {@link TreeItem} subtree for a brand-new {@link FileNode}
     *  (i.e. one with no corresponding existing {@link TreeItem} to merge into) — every
     *  descendant, not just the immediate children, so restoring a tree in one shot
     *  (e.g. loading the cached scan on startup) doesn't stop after a couple of levels. */
    private TreeItem<FileNode> buildTreeItem(FileNode node) {
        var item = new TreeItem<>(node);
        if (node.isDirectory()) {
            for (var child : node.getChildren()) {
                item.getChildren().add(buildTreeItem(child));
            }
        }
        return item;
    }

    private void highlightInTree(FileNode target) {
        if (treeView.getRoot() == null || target == null || target.getPath() == null) return;
        expandAndSelect(treeView.getRoot(), target.getPath());
    }

    private boolean expandAndSelect(TreeItem<FileNode> item, Path targetPath) {
        if (item.getValue() != null && targetPath.equals(item.getValue().getPath())) {
            treeView.getSelectionModel().select(item);
            treeView.scrollTo(treeView.getSelectionModel().getSelectedIndex());
            return true;
        }
        for (var child : item.getChildren()) {
            if (targetPath.startsWith(child.getValue() != null && child.getValue().getPath() != null
                    ? child.getValue().getPath() : Path.of(""))) {
                item.setExpanded(true);
                if (expandAndSelect(child, targetPath)) return true;
            }
        }
        return false;
    }

    private void applyTheme() {
        var scene = stage.getScene();
        if (scene == null) return;
        scene.getStylesheets().removeIf(s -> s.endsWith("dark.css"));
        if (darkModeCheck.isSelected()) {
            scene.getStylesheets().add(getClass().getResource("/styles/dark.css").toExternalForm());
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

    private void showSettingsDialog() {
        var s = Settings.get();
        var shaCb = new CheckBox("SHA-256 duplicate detection (slow, exact)");
        shaCb.setSelected(s.duplicateSHA256);

        var hiddenCb = new CheckBox("Show hidden files on startup");
        hiddenCb.setSelected(s.scanHidden);

        var darkCb = new CheckBox("Dark mode on startup");
        darkCb.setSelected(s.darkMode);

        var trashCb = new CheckBox("Move deleted items to Trash/Recycle Bin (recommended)");
        trashCb.setSelected(s.moveToTrash);
        if (!DeletionService.isTrashSupported()) {
            trashCb.setSelected(false);
            trashCb.setDisable(true);
            trashCb.setText(trashCb.getText() + " \u2014 not supported on this system");
        }

        var sortCb = new ComboBox<String>(FXCollections.observableArrayList("size", "name", "date"));
        sortCb.setValue(s.defaultSort);

        var form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.setPadding(new Insets(15));
        form.add(new Label("Duplicates:"), 0, 0);
        form.add(shaCb, 1, 0);
        form.add(new Label("Scanning:"), 0, 1);
        form.add(hiddenCb, 1, 1);
        form.add(new Label("Appearance:"), 0, 2);
        form.add(darkCb, 1, 2);
        form.add(new Label("Deletion:"), 0, 3);
        form.add(trashCb, 1, 3);
        form.add(new Label("Default sort:"), 0, 4);
        form.add(sortCb, 1, 4);

        var rootsLabel = new Label("Scan roots for projects (one per line):");
        form.add(rootsLabel, 0, 5);
        var rootsArea = new TextArea(String.join("\n", s.scanRoots));
        rootsArea.setPrefRowCount(4);
        rootsArea.setPrefWidth(350);
        form.add(rootsArea, 1, 5);

        var projectEnabledCb = new CheckBox("Enable project scanning");
        projectEnabledCb.setSelected(s.projectScanEnabled);
        form.add(projectEnabledCb, 0, 6, 2, 1);

        var depthLabel = new Label("Max scan depth:");
        form.add(depthLabel, 0, 7);
        var depthField = new TextField(String.valueOf(s.projectScanDepth));
        depthField.setPrefWidth(60);
        form.add(depthField, 1, 7);

        var chartDepthLabel = new Label("Chart nesting depth:");
        form.add(chartDepthLabel, 0, 8);
        var chartDepthField = new TextField(String.valueOf(s.chartRenderDepth));
        chartDepthField.setPrefWidth(60);
        form.add(chartDepthField, 1, 8);
        var chartDepthNote = new Label("How many nested levels Treemap/Rings subdivide before folding deeper content into one block (like GNOME Baobab). Lower = cleaner but less detail at a glance; click to drill down either way.");
        chartDepthNote.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        chartDepthNote.setWrapText(true);
        chartDepthNote.setMaxWidth(350);
        form.add(chartDepthNote, 0, 9, 2, 1);

        var note = new Label("Duplicate detection uses SHA-256. Disabled by default (slow on large scans).");
        note.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        form.add(note, 0, 10, 2, 1);

        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Settings");
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            s.duplicateSHA256 = shaCb.isSelected();
            s.scanHidden = hiddenCb.isSelected();
            s.darkMode = darkCb.isSelected();
            s.moveToTrash = trashCb.isSelected();
            s.defaultSort = sortCb.getValue();
            s.scanRoots = List.of(rootsArea.getText().split("\\n"))
                    .stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
            s.projectScanEnabled = projectEnabledCb.isSelected();
            try { s.projectScanDepth = Integer.parseInt(depthField.getText()); } catch (NumberFormatException ignored) {}
            try { s.chartRenderDepth = Integer.parseInt(chartDepthField.getText()); } catch (NumberFormatException ignored) {}
            if (s.chartRenderDepth < 1) s.chartRenderDepth = 1;
            if (s.chartRenderDepth > 10) s.chartRenderDepth = 10;
            s.save();

            hiddenFilesCheck.setSelected(s.scanHidden);
            darkModeCheck.setSelected(s.darkMode);
            applyScanSettings();
            applyTheme();
            sortCombo.setValue("Sort by " + capitalize(s.defaultSort));
            if (currentRoot != null) {
                rescanCurrentRoot();
            } else {
                renderCurrentView();
            }
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public void show() {
        stage.show();
        var tree = CacheManager.loadLastScan();
        if (tree != null) {
            currentRoot = tree;
            scannedRootPath = tree.getPath();
            applySort(tree);
            statusLabel.setText("Loaded: " + tree.getPath());
            sizeLabel.setText(SizeFormat.format(tree.getSize()));
            updateCounts(tree);
            updateDiskInfo(tree.getPath());
            populateTree(tree);
            resetView(tree);
            bottomTabs.setRoot(tree);
        }
    }
}
