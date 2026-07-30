package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;

public class BottomTabs {

    private final TabPane tabPane;
    private final VBox emptyLabel;

    private final TableView<FileNode> largestTable;
    private final TableView<FileAnalysis.FileTypeStat> typesTable;
    private final VBox duplicatesBox;
    private final TableView<FileNode> emptyDirsTable;
    private final TableView<FileNode> oldFilesTable;
    private final ComboBox<String> ageFilter;

    private final VBox cleanupBox;
    private final java.util.List<SystemCleanup.Target> cleanupTargets;

    private final VBox buildArtifactsBox;

    private final VBox compressBox;
    private final ComboBox<String> groupMode;
    private final VBox groupBox;
    private final VBox snapshotBox;
    private final ComboBox<String> snapshotCombo;

    private FileNode root;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    public BottomTabs() {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        emptyLabel = new VBox(new Label("Scan a folder to see analysis"));
        emptyLabel.setPadding(new Insets(20));
        emptyLabel.setAlignment(javafx.geometry.Pos.CENTER);

        largestTable = createLargestTable();
        typesTable = createTypesTable();
        duplicatesBox = new VBox(10);
        duplicatesBox.setPadding(new Insets(5));
        emptyDirsTable = createEmptyDirsTable();
        oldFilesTable = createOldFilesTable();

        ageFilter = new ComboBox<>(FXCollections.observableArrayList(
                "30 days", "90 days", "180 days", "365 days"));
        ageFilter.setValue("90 days");
        ageFilter.setOnAction(e -> refreshOldFiles());

        tabPane.getTabs().addAll(
                createTab("Largest Files", wrap(largestTable)),
                createTab("File Types", wrap(typesTable)),
                createTab("Duplicates", wrapScroll(duplicatesBox)),
                createTab("Empty Dirs", wrap(emptyDirsTable)),
                createTab("Old Files", buildOldFilesPanel())
        );

        cleanupTargets = SystemCleanup.targets();
        cleanupBox = new VBox(10);
        cleanupBox.setPadding(new Insets(5));
        if (!cleanupTargets.isEmpty()) {
            tabPane.getTabs().add(createTab("Cleanup", buildCleanupPanel()));
        }

        buildArtifactsBox = new VBox(10);
        buildArtifactsBox.setPadding(new Insets(5));
        tabPane.getTabs().add(createTab("Project Cleanup", buildArtifactsPanel()));

        compressBox = new VBox(10);
        compressBox.setPadding(new Insets(5));
        tabPane.getTabs().add(createTab("Compress", wrapScroll(compressBox)));

        groupMode = new ComboBox<>(FXCollections.observableArrayList("File Type", "Age", "Owner"));
        groupMode.setValue("File Type");
        groupBox = new VBox(10);
        groupBox.setPadding(new Insets(5));
        tabPane.getTabs().add(createTab("Groups", buildGroupPanel()));

        snapshotCombo = new ComboBox<>();
        snapshotCombo.setPromptText("Select previous snapshot");
        snapshotCombo.setPrefWidth(200);
        snapshotBox = new VBox(10);
        snapshotBox.setPadding(new Insets(5));
        tabPane.getTabs().add(createTab("Snapshots", buildSnapshotPanel()));
    }

    public TabPane getPane() { return tabPane; }

    public void setRoot(FileNode node) {
        this.root = node;
        if (node == null) {
            tabPane.setVisible(false);
            return;
        }
        tabPane.setVisible(true);
        refreshLargest();
        refreshTypes();
        refreshDuplicates();
        refreshEmptyDirs();
        refreshOldFiles();
        refreshCleanup();
        refreshBuildArtifacts();
        refreshCompress();
        refreshGroups();
        refreshSnapshots();
    }

    private void refreshLargest() {
        if (root == null) return;
        var files = FileAnalysis.largestFiles(root, 100);
        largestTable.setItems(FXCollections.observableArrayList(files));
    }

    private void refreshEmptyDirs() {
        if (root == null) return;
        var dirs = FileAnalysis.findEmptyDirs(root);
        emptyDirsTable.setItems(FXCollections.observableArrayList(dirs));
    }

    private TableView<FileNode> createEmptyDirsTable() {
        var table = new TableView<FileNode>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        var nameCol = new TableColumn<FileNode, String>("Directory");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(300);

        var pathCol = new TableColumn<FileNode, String>("Path");
        pathCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPath().toString()));

        table.getColumns().addAll(nameCol, pathCol);
        return table;
    }

    private void refreshTypes() {
        if (root == null) return;
        var map = FileAnalysis.fileTypeBreakdown(root);
        var list = map.values().stream()
                .sorted(Comparator.comparingLong(FileAnalysis.FileTypeStat::getTotalSize).reversed())
                .collect(Collectors.toList());
        typesTable.setItems(FXCollections.observableArrayList(list));
    }

    private void refreshDuplicates() {
        if (root == null) return;
        var groups = FileAnalysis.findDuplicates(root);
        duplicatesBox.getChildren().clear();

        if (groups.isEmpty()) {
            duplicatesBox.getChildren().add(new Label("No duplicates found."));
            if (!Settings.get().duplicateSHA256) {
                duplicatesBox.getChildren().add(new Label(
                        "Size-only matching. Enable SHA-256 in Settings for exact matching."));
            }
            return;
        }

        if (!Settings.get().duplicateSHA256) {
            duplicatesBox.getChildren().add(new Label(
                    "⚠ Fast mode: matching by size + first 8 KB hash. Enable SHA-256 in Settings for full content matching."));
        }

        long wasted = FileAnalysis.totalDuplicateWaste(groups);
        duplicatesBox.getChildren().add(new Label(
                "Showing top " + groups.size() + " duplicate groups. Wasted space: " +
                MainWindow.formatSize(wasted)));
        duplicatesBox.getChildren().add(new Label(
                "Excludes: .git/, node_modules/, target/, __pycache__/, build/, dist/, vendor/, .venv/"));

        for (var group : groups) {
            var groupBox = new VBox(3);
            groupBox.setStyle("-fx-border-color: #ddd; -fx-border-radius: 4; -fx-padding: 5;");

            String prefix = FileAnalysis.commonPathPrefix(group.files);
            var header = new Label(group.files.size() + " identical files × " +
                    MainWindow.formatSize(group.fileSize) + " each  (waste: " +
                    MainWindow.formatSize(group.wastedSize()) + ")");
            header.setStyle("-fx-font-weight: bold;");
            groupBox.getChildren().add(header);

            if (!prefix.isEmpty()) {
                var loc = new Label("  in " + prefix);
                loc.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
                groupBox.getChildren().add(loc);
            }

            int shown = 0;
            for (var file : group.files) {
                if (shown++ >= 3) break;
                var cb = new CheckBox(file.getPath().toString() + "  (" +
                        MainWindow.formatSize(file.getSize()) + ")");
                groupBox.getChildren().add(cb);
            }
            if (group.files.size() > 3) {
                groupBox.getChildren().add(new Label("  … and " + (group.files.size() - 3) +
                        " more files"));
            }

            duplicatesBox.getChildren().add(groupBox);
        }

        var deleteBtn = new Button("Delete Selected");
        deleteBtn.setOnAction(e -> deleteSelectedDuplicates());
        duplicatesBox.getChildren().add(deleteBtn);
    }

    private void deleteSelectedDuplicates() {
        int deleted = 0;
        for (var node : duplicatesBox.getChildren()) {
            if (node instanceof VBox groupBox) {
                for (var child : groupBox.getChildren()) {
                    if (child instanceof CheckBox cb && cb.isSelected()) {
                        try {
                            Files.delete(Path.of(cb.getText().split("  \\(")[0]));
                            deleted++;
                        } catch (IOException ignored) {}
                    }
                }
            }
        }
        if (deleted > 0) refreshDuplicates();
    }

    private void refreshOldFiles() {
        if (root == null) return;
        String selected = ageFilter.getValue();
        long millis = switch (selected) {
            case "30 days" -> 30L;
            case "90 days" -> 90L;
            case "180 days" -> 180L;
            case "365 days" -> 365L;
            default -> 90L;
        } * 24 * 3600 * 1000;

        var files = FileAnalysis.oldFiles(root, millis);
        oldFilesTable.setItems(FXCollections.observableArrayList(files));
    }

    private TableView<FileNode> createLargestTable() {
        var table = new TableView<FileNode>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        var nameCol = new TableColumn<FileNode, String>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(300);

        var sizeCol = new TableColumn<FileNode, String>("Size");
        sizeCol.setCellValueFactory(c ->
                new SimpleStringProperty(MainWindow.formatSize(c.getValue().getSize())));
        sizeCol.setPrefWidth(100);

        var pathCol = new TableColumn<FileNode, String>("Path");
        pathCol.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getPath().toString()));

        table.getColumns().addAll(nameCol, sizeCol, pathCol);
        return table;
    }

    private TableView<FileAnalysis.FileTypeStat> createTypesTable() {
        var table = new TableView<FileAnalysis.FileTypeStat>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        var extCol = new TableColumn<FileAnalysis.FileTypeStat, String>("Extension");
        extCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getExtension()));
        extCol.setPrefWidth(150);

        var countCol = new TableColumn<FileAnalysis.FileTypeStat, Number>("Files");
        countCol.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().getCount()));

        var sizeCol = new TableColumn<FileAnalysis.FileTypeStat, String>("Total Size");
        sizeCol.setCellValueFactory(c ->
                new SimpleStringProperty(MainWindow.formatSize(c.getValue().getTotalSize())));

        table.getColumns().addAll(extCol, countCol, sizeCol);
        return table;
    }

    private TableView<FileNode> createOldFilesTable() {
        var table = new TableView<FileNode>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        var nameCol = new TableColumn<FileNode, String>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(250);

        var ageCol = new TableColumn<FileNode, String>("Last Modified");
        ageCol.setCellValueFactory(c -> {
            long millis = millis(c.getValue());
            return new SimpleStringProperty(
                    millis > 0 ? DATE_FMT.format(Instant.ofEpochMilli(millis)) : "unknown");
        });
        ageCol.setPrefWidth(130);

        var sizeCol = new TableColumn<FileNode, String>("Size");
        sizeCol.setCellValueFactory(c ->
                new SimpleStringProperty(MainWindow.formatSize(c.getValue().getSize())));
        sizeCol.setPrefWidth(100);

        var pathCol = new TableColumn<FileNode, String>("Path");
        pathCol.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getPath().toString()));

        table.getColumns().addAll(nameCol, ageCol, sizeCol, pathCol);
        return table;
    }

    private VBox buildOldFilesPanel() {
        var label = new Label("Files not modified for:");
        var box = new VBox(8, label, ageFilter, oldFilesTable);
        VBox.setVgrow(oldFilesTable, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    private long millis(FileNode file) {
        try {
            return Files.getLastModifiedTime(file.getPath()).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Tab createTab(String title, javafx.scene.Node content) {
        var tab = new Tab(title);
        tab.setContent(content);
        return tab;
    }

    private static javafx.scene.Node wrap(TableView<?> table) {
        var box = new VBox(table);
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    private static javafx.scene.Node wrapScroll(VBox content) {
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        return scroll;
    }

    private void refreshCleanup() {
        cleanupBox.getChildren().clear();
        if (cleanupTargets.isEmpty()) return;

        var header = new Label("System & Developer caches — total: scanning in parallel...");
        header.setStyle("-fx-font-weight: bold;");
        cleanupBox.getChildren().add(header);
        cleanupBox.getChildren().add(new Label("Scanning " + cleanupTargets.size() + " targets..."));

        var thread = new Thread(() -> {
            var sizes = new java.util.concurrent.ConcurrentHashMap<SystemCleanup.Target, Long>();
            var needsElevationList = java.util.Collections.synchronizedList(new ArrayList<Path>());

            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = cleanupTargets.stream()
                        .map(target -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                            long size = SystemCleanup.calculateSize(target);
                            sizes.put(target, size);
                            var resolved = SystemCleanup.resolvePath(target);
                            if (size == 0 && resolved != null && java.nio.file.Files.exists(resolved)) {
                                needsElevationList.add(resolved);
                            }
                        }, executor))
                        .toArray(java.util.concurrent.CompletableFuture[]::new);
                java.util.concurrent.CompletableFuture.allOf(futures).join();
            }

            Platform.runLater(() -> {
                cleanupBox.getChildren().clear();
                var h = new Label("System & Developer caches — total: (calculating...)");
                h.setStyle("-fx-font-weight: bold;");
                cleanupBox.getChildren().add(h);

                var sortedTargets = new ArrayList<>(cleanupTargets);
                var grid = buildCleanupGrid(sortedTargets, sizes, h, needsElevationList);
                cleanupBox.getChildren().add(grid);
                maybeShowElevateButton(needsElevationList, grid, h);
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    private javafx.scene.layout.GridPane buildCleanupGrid(
            java.util.List<SystemCleanup.Target> targets,
            java.util.Map<SystemCleanup.Target, Long> sizes,
            Label header, java.util.List<Path> needsElevationList) {

        var grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(5);

        long grandTotal = 0;
        int row = 0;

        for (var target : targets) {
            long size = sizes.getOrDefault(target, 0L);
            grandTotal += size;
            var resolved = SystemCleanup.resolvePath(target);
            boolean locked = needsElevationList.contains(resolved);

            var nameLabel = new Label((locked ? "🔒 " : "") + target.name());
            var sizeLabel = new Label(locked ? "locked" : MainWindow.formatSize(size));
            sizeLabel.setId("size-" + row);

            grid.add(nameLabel, 0, row);
            grid.add(sizeLabel, 1, row);
            grid.add(new Label(target.description() != null ? target.description() : ""), 2, row);

            var actions = new HBox(5);
            var openBtn = new Button("Open");
            openBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6;");
            openBtn.setOnAction(e -> {
                try {
                    if (resolved != null && Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(resolved.toFile());
                    }
                } catch (IOException ignored) {}
            });

            if (target.customCommand() != null && !target.customCommand().isEmpty()) {
                var cmdBtn = new Button("Run");
                cmdBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6; -fx-text-fill: #2980b9;");
                cmdBtn.setOnAction(e -> {
                    try {
                        ProcessBuilder pb = new ProcessBuilder();
                        if (System.getProperty("os.name").toLowerCase().contains("win")) {
                            pb.command("cmd", "/c", target.customCommand());
                        } else {
                            pb.command("sh", "-c", target.customCommand());
                        }
                        pb.inheritIO().start();
                    } catch (IOException ignored) {}
                });
                actions.getChildren().add(cmdBtn);
            }

            var deleteBtn = new Button("Delete");
            deleteBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6; -fx-text-fill: #c0392b;");
            deleteBtn.setOnAction(e -> {
                var confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Delete " + target.name());
                confirm.setHeaderText("Delete " + target.name() + "?");
                confirm.setContentText((resolved != null ? resolved.toString() : "") +
                        "\nSize: " + MainWindow.formatSize(size));
                if (confirm.showAndWait().orElse(null) == ButtonType.OK && resolved != null) {
                    try {
                        deleteRecursive(resolved);
                        refreshCleanup();
                    } catch (IOException ignored) {}
                }
            });

            actions.getChildren().addAll(openBtn, deleteBtn);
            grid.add(actions, 3, row);
            row++;
        }

        header.setText("System & Developer caches — total: " + MainWindow.formatSize(grandTotal));
        return grid;
    }

    private void maybeShowElevateButton(java.util.List<Path> needsElevation,
                                         javafx.scene.layout.GridPane grid, Label header) {
        if (needsElevation.isEmpty()) return;

        var elevateBtn = new Button("Scan as Root");
        elevateBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold;");
        elevateBtn.setOnAction(e -> {
            elevateBtn.setDisable(true);
            elevateBtn.setText("Requesting elevated access...");
            javafx.application.Platform.runLater(() -> {
                var sizes = SystemCleanup.calculateSizesViaElevation(needsElevation);
                javafx.application.Platform.runLater(() -> {
                    for (var entry : sizes.entrySet()) {
                        for (int i = 0; i < cleanupTargets.size(); i++) {
                            var t = cleanupTargets.get(i);
                            var r = SystemCleanup.resolvePath(t);
                            if (r != null && r.equals(entry.getKey())) {
                                var label = (Label) grid.lookup("#size-" + i);
                                if (label != null) label.setText(MainWindow.formatSize(entry.getValue()));
                                break;
                            }
                        }
                    }
                    elevateBtn.setText("Scan as Root");
                    elevateBtn.setDisable(false);
                    header.setText("System & Developer caches — total: (refresh to recalculate)");
                });
            });
        });
        cleanupBox.getChildren().add(elevateBtn);
    }

    private javafx.scene.Node buildCleanupPanel() {
        var scroll = new ScrollPane(cleanupBox);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

        var refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> refreshCleanup());

        var topBar = new HBox(10, refreshBtn, new Label(
                "Edit ~/.filescanner/cleanup-targets.json to add your own paths"));
        topBar.setPadding(new Insets(0, 0, 5, 0));

        var box = new VBox(8, topBar, scroll);
        return box;
    }

    private void refreshBuildArtifacts() {
        buildArtifactsBox.getChildren().clear();

        if (!Settings.get().projectScanEnabled) {
            buildArtifactsBox.getChildren().add(new Label("Project scanning is disabled. Enable in Settings."));
            return;
        }

        buildArtifactsBox.getChildren().add(new Label("Scanning project roots..."));

        var thread = new Thread(() -> {
            var roots = SystemCleanup.scanRoots();
            var artifacts = SystemCleanup.findBuildArtifacts(roots);

            Platform.runLater(() -> {
                buildArtifactsBox.getChildren().clear();

                if (artifacts.isEmpty()) {
                    buildArtifactsBox.getChildren().add(new Label(
                            "No build artifacts found in scan roots."));
                    return;
                }

                var totalSize = new java.util.concurrent.atomic.AtomicLong();
                var grid = new javafx.scene.layout.GridPane();
                grid.setHgap(10);
                grid.setVgap(5);

                int row = 0;
                String lastType = "";

                for (var a : artifacts) {
                    long size = SystemCleanup.walkSizeSafe(a.path());
                    totalSize.addAndGet(size);

                    if (!a.projectType().displayName().equals(lastType)) {
                        lastType = a.projectType().displayName();
                        var typeLabel = new Label(lastType + " (" + a.projectType().name() + ")");
                        typeLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #3498db;");
                        grid.add(typeLabel, 0, row++, 4, 1);
                    }

                    var nameLabel = new Label("  " + a.artifactName());
                    var sizeLabel = new Label(MainWindow.formatSize(size));
                    var pathLabel = new Label(a.projectDir().toString());
                    pathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

                    grid.add(nameLabel, 0, row);
                    grid.add(sizeLabel, 1, row);
                    grid.add(pathLabel, 2, row);

                    var actions = new HBox(5);
                    var openBtn = new Button("Open");
                    openBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6;");
                    openBtn.setOnAction(e -> {
                        try {
                            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(a.path().toFile());
                        } catch (IOException ignored) {}
                    });

                    var deleteBtn = new Button("Delete");
                    deleteBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6; -fx-text-fill: #c0392b;");
                    deleteBtn.setOnAction(e -> {
                        var confirm = new Alert(Alert.AlertType.CONFIRMATION);
                        confirm.setTitle("Delete " + a.artifactName());
                        confirm.setHeaderText("Delete build artifact?");
                        confirm.setContentText(a.path() + "\nSize: " + MainWindow.formatSize(size));
                        if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
                            try { deleteRecursive(a.path()); refreshBuildArtifacts(); }
                            catch (IOException ignored) {}
                        }
                    });

                    actions.getChildren().addAll(openBtn, deleteBtn);
                    grid.add(actions, 3, row);
                    row++;
                }

                var header = new Label("Project Build Artifacts — total: " + MainWindow.formatSize(totalSize.get()) +
                        " in " + artifacts.size() + " artifacts");
                header.setStyle("-fx-font-weight: bold;");
                buildArtifactsBox.getChildren().add(header);

                var deleteAllBtn = new Button("Delete All (" + MainWindow.formatSize(totalSize.get()) + ")");
                deleteAllBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold;");
                final long finalTotal = totalSize.get();
                deleteAllBtn.setOnAction(e -> {
                    var confirm = new Alert(Alert.AlertType.CONFIRMATION);
                    confirm.setTitle("Delete All Build Artifacts");
                    confirm.setHeaderText("Delete " + artifacts.size() + " build artifacts?");
                    confirm.setContentText("Total: " + MainWindow.formatSize(finalTotal) +
                            "\n\nThese can be regenerated by rebuilding the projects.");
                    if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
                        for (var a : artifacts) {
                            try { deleteRecursive(a.path()); } catch (IOException ignored) {}
                        }
                        refreshBuildArtifacts();
                    }
                });
                buildArtifactsBox.getChildren().add(deleteAllBtn);
                buildArtifactsBox.getChildren().add(grid);
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    private javafx.scene.Node buildArtifactsPanel() {
        var scroll = new ScrollPane(buildArtifactsBox);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

        var refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> refreshBuildArtifacts());

        var topBar = new HBox(10, refreshBtn, new Label(
                "Scans configured roots for build folders. Edit scan roots in Settings."));
        topBar.setPadding(new Insets(0, 0, 5, 0));

        return new VBox(8, topBar, scroll);
    }

    private void refreshCompress() {
        compressBox.getChildren().clear();
        if (root == null) return;

        var estimate = CompressionEstimate.estimate(root);
        compressBox.getChildren().add(new Label("Compression Potential"));
        compressBox.getChildren().add(new Label("Original: " + MainWindow.formatSize(estimate.originalSize())));
        compressBox.getChildren().add(new Label("Estimated after compression: " + MainWindow.formatSize(estimate.estimatedCompressed())));
        compressBox.getChildren().add(new Label("Could save: " + MainWindow.formatSize(estimate.savings()) +
                " (" + String.format("%.0f%%", (1 - estimate.ratio()) * 100) + ")"));
        compressBox.getChildren().add(new Label("Strategy: " + estimate.strategy()));
        compressBox.getChildren().add(new Label(""));

        var byCat = new java.util.HashMap<FileTypeCategory, long[]>();
        var files = FileAnalysis.flattenFiles(root);
        for (var f : files) {
            var cat = FileTypeCategory.forFile(f.getName());
            var arr = byCat.computeIfAbsent(cat, k -> new long[2]);
            arr[0] += f.getSize();
            arr[1] += CompressionEstimate.estimateCompressedSize(f);
        }

        var grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(4);
        grid.add(new Label("Category"), 0, 0);
        grid.add(new Label("Original"), 1, 0);
        grid.add(new Label("Compressed"), 2, 0);
        grid.add(new Label("Savings"), 3, 0);

        int row = 1;
        for (var entry : byCat.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .toList()) {
            long orig = entry.getValue()[0];
            long compressed = entry.getValue()[1];
            long save = orig - compressed;
            grid.add(new Label(entry.getKey().name()), 0, row);
            grid.add(new Label(MainWindow.formatSize(orig)), 1, row);
            grid.add(new Label(MainWindow.formatSize(compressed)), 2, row);
            grid.add(new Label(MainWindow.formatSize(save)), 3, row);
            row++;
        }
        compressBox.getChildren().add(grid);
    }

    private void refreshGroups() {
        groupBox.getChildren().clear();
        if (root == null) return;

        String mode = groupMode.getValue();
        var groups = switch (mode) {
            case "Age" -> FileGrouper.byAge(root);
            case "Owner" -> FileGrouper.byOwner(root);
            default -> FileGrouper.byFileType(root);
        };

        long totalSize = groups.stream().mapToLong(FileGrouper.Group::totalSize).sum();
        var header = new Label(mode + " grouping — " + groups.size() + " groups, " +
                MainWindow.formatSize(totalSize));
        header.setStyle("-fx-font-weight: bold;");
        groupBox.getChildren().add(header);

        var grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(4);
        grid.add(new Label("Group"), 0, 0);
        grid.add(new Label("Files"), 1, 0);
        grid.add(new Label("Total Size"), 2, 0);
        grid.add(new Label("Share"), 3, 0);

        int row = 1;
        for (var g : groups) {
            double pct = totalSize > 0 ? (double) g.totalSize() / totalSize * 100 : 0;
            grid.add(new Label(g.name()), 0, row);
            grid.add(new Label(String.valueOf(g.fileCount())), 1, row);
            grid.add(new Label(MainWindow.formatSize(g.totalSize())), 2, row);
            grid.add(new Label(String.format("%.1f%%", pct)), 3, row);
            row++;
        }
        groupBox.getChildren().add(grid);
    }

    private javafx.scene.Node buildGroupPanel() {
        groupMode.setOnAction(e -> refreshGroups());
        var topBar = new HBox(10, new Label("Group by:"), groupMode,
                new Label("Alternative grouping (not by folder)"));
        topBar.setPadding(new Insets(0, 0, 5, 0));

        var scroll = new ScrollPane(groupBox);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        return new VBox(8, topBar, scroll);
    }

    private void refreshSnapshots() {
        snapshotBox.getChildren().clear();
        var snaps = SnapshotManager.listSnapshots();
        snapshotCombo.setItems(FXCollections.observableArrayList(snaps));
        if (!snaps.isEmpty()) snapshotCombo.setValue(snaps.get(0));

        var saveLabel = new Label("Save current scan as snapshot:");
        var nameField = new TextField();
        nameField.setPromptText("Snapshot name");
        nameField.setPrefWidth(200);
        var saveBtn = new Button("Save Snapshot");
        saveBtn.setOnAction(e -> {
            if (nameField.getText().isEmpty() || root == null) return;
            try {
                SnapshotManager.saveSnapshot(root, nameField.getText());
                statusLabel("Snapshot saved: " + nameField.getText());
                refreshSnapshots();
            } catch (IOException ex) {
                statusLabel("Failed to save: " + ex.getMessage());
            }
        });

        snapshotBox.getChildren().addAll(saveLabel,
                new HBox(10, nameField, saveBtn));

        if (snaps.isEmpty()) {
            snapshotBox.getChildren().add(new Label("No previous snapshots."));
            return;
        }

        snapshotBox.getChildren().add(new Separator());
        snapshotBox.getChildren().add(new Label("Compare with previous snapshot:"));

        var compareBox = new HBox(10, snapshotCombo);
        var compareBtn = new Button("Compare");
        compareBtn.setOnAction(e -> {
            var selected = snapshotCombo.getValue();
            if (selected == null || root == null) return;
            try {
                var diff = SnapshotManager.compare(root, selected);
                showDiff(diff);
            } catch (IOException ex) {
                statusLabel("Compare failed: " + ex.getMessage());
            }
        });
        compareBox.getChildren().add(compareBtn);
        snapshotBox.getChildren().add(compareBox);

        var deleteBtn = new Button("Delete Selected Snapshot");
        deleteBtn.setStyle("-fx-text-fill: #c0392b;");
        deleteBtn.setOnAction(e -> {
            var selected = snapshotCombo.getValue();
            if (selected == null) return;
            try {
                SnapshotManager.deleteSnapshot(selected);
                refreshSnapshots();
            } catch (IOException ignored) {}
        });
        snapshotBox.getChildren().add(deleteBtn);
    }

    private void showDiff(SnapshotManager.SnapshotDiff diff) {
        var dialog = new Dialog<Void>();
        dialog.setTitle("Snapshot Comparison");
        dialog.setWidth(600);
        dialog.setHeight(500);

        var summary = SnapshotManager.summarize(diff);
        var content = new VBox(8);
        content.setPadding(new Insets(10));
        content.getChildren().add(new Label("Changes since previous snapshot:"));
        content.getChildren().add(new Label(""));

        if (!diff.hasChanges()) {
            content.getChildren().add(new Label("No changes detected."));
        } else {
            if (summary.addedCount() > 0) content.getChildren().add(new Label(
                    "Added: " + summary.addedCount() + " files (" + MainWindow.formatSize(summary.totalAdded()) + ")"));
            if (summary.removedCount() > 0) content.getChildren().add(new Label(
                    "Removed: " + summary.removedCount() + " files (" + MainWindow.formatSize(summary.totalRemoved()) + ")"));
            if (summary.grownCount() > 0) content.getChildren().add(new Label(
                    "Grown: " + summary.grownCount() + " files (+" + MainWindow.formatSize(summary.totalGrown()) + ")"));
            if (summary.shrunkCount() > 0) content.getChildren().add(new Label(
                    "Shrunk: " + summary.shrunkCount() + " files (-" + MainWindow.formatSize(summary.totalShrunk()) + ")"));
            content.getChildren().add(new Label("Net change: " + MainWindow.formatSize(summary.netChange())));
        }

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private javafx.scene.Node buildSnapshotPanel() {
        var scroll = new ScrollPane(snapshotBox);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        return scroll;
    }

    private void statusLabel(String text) {
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Info");
        alert.setHeaderText(null);
        alert.setContentText(text);
        alert.show();
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } else {
            Files.delete(path);
        }
    }
}
