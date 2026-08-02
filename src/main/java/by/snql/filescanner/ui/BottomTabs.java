package by.snql.filescanner.ui;

import by.snql.filescanner.config.Settings;
import by.snql.filescanner.core.analysis.CompressionEstimate;
import by.snql.filescanner.core.analysis.FileAnalysis;
import by.snql.filescanner.core.analysis.FileGrouper;
import by.snql.filescanner.core.analysis.FileCategory;
import by.snql.filescanner.core.cleanup.DeletionService;
import by.snql.filescanner.core.cleanup.SystemCleanup;
import by.snql.filescanner.core.export.SnapshotManager;
import by.snql.filescanner.core.util.SizeFormat;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class BottomTabs {

    private final TabPane tabPane;

    private final TableView<FileNode> largestTable;
    private final TableView<FileAnalysis.FileTypeStat> typesTable;
    private final VBox duplicatesBox;
    private final TableView<FileNode> emptyDirsTable;
    private final TableView<FileNode> oldFilesTable;
    private final ComboBox<String> ageFilter;

    // "Developer Cleanup" tab — merges what used to be two separate tabs ("Cleanup" for
    // OS/tool caches and "Project Cleanup" for build artifacts) plus a new Docker
    // breakdown, into one place: everything a developer would want to reclaim disk space
    // from, in one scrollable view instead of three disconnected tabs.
    private final VBox devCleanupBox;
    private final Label totalReclaimableHeader = new Label();
    private final VBox artifactsSection;
    private final VBox dockerSection;
    private final VBox cachesSection;
    private final List<SystemCleanup.Target> cleanupTargets;

    // Tracks each section's known reclaimable total (-1 = not computed yet) so the combined
    // header can be updated incrementally as each of the three sections finishes its own
    // (independent, concurrent) background scan, without waiting for the slowest one.
    private long artifactsReclaimable = -1;
    private long dockerReclaimable = -1;
    private long cachesReclaimable = -1;

    private final VBox compressBox;
    private final ComboBox<String> groupMode;
    private final VBox groupBox;
    private final VBox snapshotBox;
    private final ComboBox<String> snapshotCombo;

    private FileNode root;

    /** Bumped every time {@link #setRoot} is called; background tasks check it before
     *  touching the UI so results from a superseded scan never overwrite newer data. */
    private final AtomicLong generation = new AtomicLong();
    private final boolean[] dirty = new boolean[TAB_COUNT];
    private java.util.function.Consumer<String> onStatus = msg -> {};

    private static final int TAB_LARGEST = 0, TAB_TYPES = 1, TAB_DUPLICATES = 2, TAB_EMPTY = 3,
            TAB_OLD = 4, TAB_DEV_CLEANUP = 5, TAB_COMPRESS = 6, TAB_GROUPS = 7, TAB_SNAPSHOTS = 8;
    private static final int TAB_COUNT = 9;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    public BottomTabs() {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

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

        cleanupTargets = SystemCleanup.targets();
        artifactsSection = new VBox(8);
        dockerSection = new VBox(8);
        cachesSection = new VBox(8);
        totalReclaimableHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #27ae60;");
        devCleanupBox = new VBox(16, totalReclaimableHeader, new Separator(),
                artifactsSection, new Separator(), dockerSection, new Separator(), cachesSection);
        devCleanupBox.setPadding(new Insets(5));

        compressBox = new VBox(10);
        compressBox.setPadding(new Insets(5));

        groupMode = new ComboBox<>(FXCollections.observableArrayList("File Type", "Age", "Owner"));
        groupMode.setValue("File Type");
        groupBox = new VBox(10);
        groupBox.setPadding(new Insets(5));

        snapshotCombo = new ComboBox<>();
        snapshotCombo.setPromptText("Select previous snapshot");
        snapshotCombo.setPrefWidth(200);
        snapshotBox = new VBox(10);
        snapshotBox.setPadding(new Insets(5));

        tabPane.getTabs().addAll(
                createTab("Largest Files", wrap(largestTable)),
                createTab("File Types", wrap(typesTable)),
                createTab("Duplicates", wrapScroll(duplicatesBox)),
                createTab("Empty Dirs", wrap(emptyDirsTable)),
                createTab("Old Files", buildOldFilesPanel()));

        tabPane.getTabs().add(createTab("Developer Cleanup", buildDevCleanupPanel()));
        tabPane.getTabs().add(createTab("Compress", wrapScroll(compressBox)));
        tabPane.getTabs().add(createTab("Groups", buildGroupPanel()));
        tabPane.getTabs().add(createTab("Snapshots", buildSnapshotPanel()));

        tabPane.getSelectionModel().selectedIndexProperty().addListener((obs, oldIdx, newIdx) -> {
            refreshIfDirty(newIdx.intValue());
        });
    }

    public TabPane getPane() { return tabPane; }

    /** Called by the host UI to receive short human-readable status/error messages. */
    public void setOnStatus(java.util.function.Consumer<String> reporter) {
        this.onStatus = reporter;
    }

    private void reportStatus(String msg) {
        onStatus.accept(msg);
    }

    public void setRoot(FileNode node) {
        this.root = node;
        generation.incrementAndGet();
        if (node == null) {
            tabPane.setVisible(false);
            return;
        }
        tabPane.setVisible(true);
        java.util.Arrays.fill(dirty, true);
        refreshIfDirty(tabPane.getSelectionModel().getSelectedIndex());
    }

    private void refreshIfDirty(int index) {
        if (index < 0 || index >= TAB_COUNT || !dirty[index] || root == null) return;
        dirty[index] = false;
        switch (index) {
            case TAB_LARGEST -> refreshLargest();
            case TAB_TYPES -> refreshTypes();
            case TAB_DUPLICATES -> refreshDuplicates();
            case TAB_EMPTY -> refreshEmptyDirs();
            case TAB_OLD -> refreshOldFiles();
            case TAB_DEV_CLEANUP -> refreshDevCleanup();
            case TAB_COMPRESS -> refreshCompress();
            case TAB_GROUPS -> refreshGroups();
            case TAB_SNAPSHOTS -> refreshSnapshots();
            default -> { }
        }
    }

    private void refreshLargest() {
        var files = FileAnalysis.largestFiles(root, 100);
        largestTable.setItems(FXCollections.observableArrayList(files));
    }

    private void refreshEmptyDirs() {
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
        var map = FileAnalysis.fileTypeBreakdown(root);
        var list = map.values().stream()
                .sorted(Comparator.comparingLong(FileAnalysis.FileTypeStat::getTotalSize).reversed())
                .collect(Collectors.toList());
        typesTable.setItems(FXCollections.observableArrayList(list));
    }

    private void refreshDuplicates() {
        if (!Settings.get().duplicateSHA256) {
            duplicatesBox.getChildren().setAll(
                    new Label("Duplicate detection is disabled."),
                    new Label("Enable SHA-256 in Settings (\u2699) for exact content-based duplicate detection."));
            return;
        }

        FileNode r = root;
        long myGen = generation.get();
        duplicatesBox.getChildren().setAll(new Label("Scanning for duplicates..."));

        Thread.ofVirtual().start(() -> {
            var groups = FileAnalysis.findDuplicates(r);
            Platform.runLater(() -> {
                if (generation.get() != myGen) return;
                renderDuplicates(groups);
            });
        });
    }

    private void renderDuplicates(List<FileAnalysis.DuplicateGroup> groups) {
        duplicatesBox.getChildren().clear();

        if (groups.isEmpty()) {
            duplicatesBox.getChildren().add(new Label("No duplicates found."));
            return;
        }

        long wasted = FileAnalysis.totalDuplicateWaste(groups);
        duplicatesBox.getChildren().add(new Label(
                "Showing top " + groups.size() + " duplicate groups. Wasted space: " + SizeFormat.format(wasted)));
        duplicatesBox.getChildren().add(new Label(
                "Excludes: .git/, node_modules/, target/, __pycache__/, build/, dist/, vendor/, .venv/"));

        for (var group : groups) {
            var groupBox = new VBox(3);
            groupBox.setStyle("-fx-border-color: #ddd; -fx-border-radius: 4; -fx-padding: 5;");

            String prefix = FileAnalysis.commonPathPrefix(group.files);
            var header = new Label(group.files.size() + " identical files \u00d7 " +
                    SizeFormat.format(group.fileSize) + " each  (waste: " +
                    SizeFormat.format(group.wastedSize()) + ")");
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
                var cb = new CheckBox(file.getPath() + "  (" + SizeFormat.format(file.getSize()) + ")");
                cb.setUserData(file); // avoid parsing the label back into a path
                groupBox.getChildren().add(cb);
            }
            if (group.files.size() > 3) {
                groupBox.getChildren().add(new Label("  \u2026 and " + (group.files.size() - 3) + " more files"));
            }

            duplicatesBox.getChildren().add(groupBox);
        }

        var deleteBtn = new Button("Delete Selected");
        deleteBtn.setOnAction(e -> deleteSelectedDuplicates());
        duplicatesBox.getChildren().add(deleteBtn);
    }

    private void deleteSelectedDuplicates() {
        var toDelete = new ArrayList<Path>();
        for (var node : duplicatesBox.getChildren()) {
            if (node instanceof VBox groupBox) {
                for (var child : groupBox.getChildren()) {
                    if (child instanceof CheckBox cb && cb.isSelected() && cb.getUserData() instanceof FileNode fn) {
                        toDelete.add(fn.getPath());
                    }
                }
            }
        }
        if (toDelete.isEmpty()) return;

        if (!confirmDelete("Delete " + toDelete.size() + " duplicate file(s)?", null)) return;

        var result = DeletionService.delete(toDelete, Settings.get().moveToTrash);
        reportDeletionResult(result);
        if (!result.deleted().isEmpty()) refreshDuplicates();
    }

    private void refreshOldFiles() {
        FileNode r = root;
        long myGen = generation.get();
        String selected = ageFilter.getValue();
        long millis = switch (selected) {
            case "30 days" -> 30L;
            case "90 days" -> 90L;
            case "180 days" -> 180L;
            case "365 days" -> 365L;
            default -> 90L;
        } * 24 * 3600 * 1000;

        Thread.ofVirtual().start(() -> {
            var files = FileAnalysis.oldFiles(r, millis);
            Platform.runLater(() -> {
                if (generation.get() != myGen) return;
                oldFilesTable.setItems(FXCollections.observableArrayList(files));
            });
        });
    }

    private TableView<FileNode> createLargestTable() {
        var table = new TableView<FileNode>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        var nameCol = new TableColumn<FileNode, String>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(300);

        var sizeCol = new TableColumn<FileNode, String>("Size");
        sizeCol.setCellValueFactory(c -> new SimpleStringProperty(SizeFormat.format(c.getValue().getSize())));
        sizeCol.setPrefWidth(100);

        var pathCol = new TableColumn<FileNode, String>("Path");
        pathCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPath().toString()));

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
        sizeCol.setCellValueFactory(c -> new SimpleStringProperty(SizeFormat.format(c.getValue().getTotalSize())));

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
            long millis = c.getValue().getLastModified();
            return new SimpleStringProperty(
                    millis > 0 ? DATE_FMT.format(Instant.ofEpochMilli(millis)) : "unknown");
        });
        ageCol.setPrefWidth(130);

        var sizeCol = new TableColumn<FileNode, String>("Size");
        sizeCol.setCellValueFactory(c -> new SimpleStringProperty(SizeFormat.format(c.getValue().getSize())));
        sizeCol.setPrefWidth(100);

        var pathCol = new TableColumn<FileNode, String>("Path");
        pathCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPath().toString()));

        table.getColumns().addAll(nameCol, ageCol, sizeCol, pathCol);
        return table;
    }

    private VBox buildOldFilesPanel() {
        var label = new Label("Files not modified for:");
        var box = new VBox(8, label, ageFilter, oldFilesTable);
        VBox.setVgrow(oldFilesTable, javafx.scene.layout.Priority.ALWAYS);
        return box;
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

    // ── Developer Cleanup (build artifacts + Docker + OS/tool caches) ──────
    //
    // Merges what used to be two separate, disconnected tabs ("Cleanup" for OS/tool
    // caches, "Project Cleanup" for build artifacts) plus a new Docker breakdown into one
    // place — the idea being a developer opens ONE tab to reclaim disk space, from Maven
    // "target/" folders to unused Docker containers, instead of hunting across several tabs.

    private void refreshDevCleanup() {
        long myGen = generation.get();
        FileNode currentRoot = root;

        artifactsReclaimable = -1;
        dockerReclaimable = -1;
        cachesReclaimable = -1;
        updateTotalReclaimableHeader();

        artifactsSection.getChildren().setAll(new Label("Scanning project roots..."));
        dockerSection.getChildren().setAll(new Label("Checking Docker..."));

        if (cleanupTargets.isEmpty()) {
            var h = new Label("Package & System Caches");
            h.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
            cachesSection.getChildren().setAll(h, new Label("No cache targets found for this OS."));
            cachesReclaimable = 0;
            updateTotalReclaimableHeader();
        } else {
            cachesSection.getChildren().setAll(new Label("Scanning " + cleanupTargets.size() + " cache targets..."));
            Thread.ofVirtual().start(() -> refreshCachesSection(myGen));
        }

        Thread.ofVirtual().start(() -> refreshArtifactsSection(myGen, currentRoot));
        Thread.ofVirtual().start(() -> refreshDockerSection(myGen));
    }

    /**
     * Recomputes the combined "how much space could I free right now" figure across all
     * three sections. Each section reports {@code -1} until its own background scan
     * finishes, so the total is shown as a running/partial figure ("so far") until all
     * three are in, rather than blocking on whichever one happens to be slowest.
     */
    private void updateTotalReclaimableHeader() {
        long sum = 0;
        boolean allKnown = true;
        if (artifactsReclaimable >= 0) sum += artifactsReclaimable; else allKnown = false;
        if (dockerReclaimable >= 0) sum += dockerReclaimable; else allKnown = false;
        if (cachesReclaimable >= 0) sum += cachesReclaimable; else allKnown = false;

        totalReclaimableHeader.setText(
                (allKnown ? "Total reclaimable: " : "Total reclaimable so far: ")
                        + SizeFormat.format(sum)
                        + (allKnown ? "" : " (still scanning\u2026)"));
    }

    private javafx.scene.Node buildDevCleanupPanel() {
        var scroll = new ScrollPane(devCleanupBox);
        scroll.setFitToWidth(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

        var refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> refreshDevCleanup());

        var topBar = new HBox(10, refreshBtn, new Label(
                "Build artifacts, Docker, and OS/tool caches in one place. " +
                        "Edit ~/.filescanner/cleanup-targets.json for custom paths, or scan roots in Settings."));
        topBar.setPadding(new Insets(0, 0, 5, 0));

        return new VBox(8, topBar, scroll);
    }

    // -- Build artifacts --

    private void refreshArtifactsSection(long myGen, FileNode currentRoot) {
        if (!Settings.get().projectScanEnabled) {
            Platform.runLater(() -> {
                if (generation.get() != myGen) return;
                artifactsSection.getChildren().setAll(new Label("Project scanning is disabled. Enable in Settings."));
                artifactsReclaimable = 0;
                updateTotalReclaimableHeader();
            });
            return;
        }

        // Scan both the user-configured roots AND whatever folder is currently open in the
        // main scan, so this "just works" over the project you're already looking at
        // instead of requiring separate scan-root configuration first.
        var roots = new ArrayList<>(SystemCleanup.scanRoots());
        var currentPath = currentRoot != null ? currentRoot.getPath() : null;
        if (currentPath != null && Files.isDirectory(currentPath) && !roots.contains(currentPath)) {
            roots.add(currentPath);
        }

        var artifacts = SystemCleanup.findBuildArtifacts(roots);
        // Compute sizes on this background thread — NOT inside Platform.runLater.
        var sizes = new java.util.HashMap<SystemCleanup.BuildArtifact, Long>();
        long total = 0;
        for (var a : artifacts) {
            long size = SystemCleanup.walkSizeSafe(a.path());
            sizes.put(a, size);
            total += size;
        }
        long finalTotal = total;

        Platform.runLater(() -> {
            if (generation.get() != myGen) return;
            artifactsReclaimable = finalTotal;
            updateTotalReclaimableHeader();
            renderBuildArtifacts(artifacts, sizes, finalTotal);
        });
    }

    private void renderBuildArtifacts(List<SystemCleanup.BuildArtifact> artifacts,
                                       Map<SystemCleanup.BuildArtifact, Long> sizes, long totalSize) {
        artifactsSection.getChildren().clear();

        var sectionHeader = new Label("Build Artifacts");
        sectionHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        artifactsSection.getChildren().add(sectionHeader);

        if (artifacts.isEmpty()) {
            artifactsSection.getChildren().add(new Label(
                    "No build artifacts found in configured roots or the currently scanned folder."));
            return;
        }

        var summary = new Label("Total: " + SizeFormat.format(totalSize) + " in " + artifacts.size() + " artifacts");
        var deleteAllBtn = new Button("Delete All (" + SizeFormat.format(totalSize) + ")");
        deleteAllBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold;");
        deleteAllBtn.setOnAction(e -> {
            if (!confirmDelete("Delete " + artifacts.size() + " build artifacts?",
                    "Total: " + SizeFormat.format(totalSize) + "\n\nThese can be regenerated by rebuilding the projects.")) {
                return;
            }
            var paths = artifacts.stream().map(SystemCleanup.BuildArtifact::path).toList();
            var result = DeletionService.delete(paths, Settings.get().moveToTrash);
            reportDeletionResult(result);
            refreshDevCleanup();
        });
        artifactsSection.getChildren().add(new HBox(10, summary, deleteAllBtn));

        var grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        setColumnConstraints(grid, col(160, 120, false), col(90, 70, false), col(280, 150, true), col(140, 130, false));

        int row = 0;
        String lastType = "";

        for (var a : artifacts) {
            long size = sizes.getOrDefault(a, 0L);

            if (!a.projectType().displayName().equals(lastType)) {
                lastType = a.projectType().displayName();
                var typeLabel = new Label(lastType + " (" + a.projectType().name() + ")");
                typeLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #3498db;");
                grid.add(typeLabel, 0, row++, 4, 1);
            }

            var nameLabel = new Label("  " + a.artifactName());
            var sizeLabel = new Label(SizeFormat.format(size));
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
                } catch (IOException ex) {
                    reportStatus("Cannot open: " + ex.getMessage());
                }
            });

            var deleteBtn = new Button("Delete");
            deleteBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6; -fx-text-fill: #c0392b;");
            deleteBtn.setOnAction(e -> {
                if (!confirmDelete("Delete build artifact?", a.path() + "\nSize: " + SizeFormat.format(size))) return;
                var result = DeletionService.delete(List.of(a.path()), Settings.get().moveToTrash);
                reportDeletionResult(result);
                refreshDevCleanup();
            });

            actions.getChildren().addAll(openBtn, deleteBtn);
            grid.add(actions, 3, row);
            row++;
        }
        artifactsSection.getChildren().add(grid);
    }

    // -- Docker --

    private void refreshDockerSection(long myGen) {
        var usage = SystemCleanup.dockerDiskUsage();
        long reclaimable = SystemCleanup.dockerTotalReclaimable(usage);
        Platform.runLater(() -> {
            if (generation.get() != myGen) return;
            dockerReclaimable = reclaimable;
            updateTotalReclaimableHeader();
            renderDocker(usage);
        });
    }

    private void renderDocker(SystemCleanup.DockerUsage usage) {
        dockerSection.getChildren().clear();

        var sectionHeader = new Label("Docker");
        sectionHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        dockerSection.getChildren().add(sectionHeader);

        if (!usage.available()) {
            dockerSection.getChildren().add(new Label(
                    usage.error() != null ? usage.error() : "Docker is not available."));
            return;
        }
        if (usage.categories().isEmpty()) {
            dockerSection.getChildren().add(new Label("Docker reported no usage data."));
            return;
        }

        dockerSection.getChildren().add(new Label(
                "Reclaimable: \u2248 " + SizeFormat.format(SystemCleanup.dockerTotalReclaimable(usage))
                        + " (estimated from Docker's own reported sizes)"));

        var grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        setColumnConstraints(grid, col(120, 90, false), col(60, 50, false), col(60, 50, false),
                col(90, 70, false), col(140, 100, true), col(90, 80, false));
        grid.add(new Label("Type"), 0, 0);
        grid.add(new Label("Total"), 1, 0);
        grid.add(new Label("Active"), 2, 0);
        grid.add(new Label("Size"), 3, 0);
        grid.add(new Label("Reclaimable"), 4, 0);

        int row = 1;
        for (var cat : usage.categories()) {
            grid.add(new Label(cat.type()), 0, row);
            grid.add(new Label(cat.total()), 1, row);
            grid.add(new Label(cat.active()), 2, row);
            grid.add(new Label(cat.size()), 3, row);
            grid.add(new Label(cat.reclaimable()), 4, row);

            var pruneArgs = SystemCleanup.dockerPruneArgsFor(cat.type());
            if (pruneArgs != null) {
                var cleanBtn = new Button("Clean");
                cleanBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6; -fx-text-fill: #c0392b;");
                cleanBtn.setOnAction(e -> confirmAndRunDocker("Remove unused " + cat.type() + "?", pruneArgs));
                grid.add(cleanBtn, 5, row);
            }
            row++;
        }
        dockerSection.getChildren().add(grid);

        var pruneAllBtn = new Button("Clean Everything Unused");
        pruneAllBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold;");
        pruneAllBtn.setOnAction(e -> confirmAndRunDocker(
                "Remove ALL unused Docker data (containers, images, networks, volumes, build cache)?",
                new String[]{"system", "prune", "-a", "-f", "--volumes"}));
        dockerSection.getChildren().add(pruneAllBtn);
    }

    private void confirmAndRunDocker(String confirmMessage, String[] args) {
        if (!confirmDelete(confirmMessage, "Runs: docker " + String.join(" ", args))) return;

        dockerSection.getChildren().add(new Label("Running docker " + String.join(" ", args) + "..."));
        long myGen = generation.get();
        Thread.ofVirtual().start(() -> {
            var result = SystemCleanup.runDocker(args);
            Platform.runLater(() -> {
                if (generation.get() != myGen) return;
                reportStatus(result.success() ? "Docker cleanup finished" : "Docker cleanup failed: " + result.output());
                refreshDockerSection(myGen);
            });
        });
    }

    // -- Package & system caches --

    private void refreshCachesSection(long myGen) {
        var sizes = new java.util.concurrent.ConcurrentHashMap<SystemCleanup.Target, Long>();
        var needsElevationList = java.util.Collections.synchronizedList(new ArrayList<Path>());

        try {
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = cleanupTargets.stream()
                        .map(target -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                            long size = SystemCleanup.calculateSize(target);
                            sizes.put(target, size);
                            var resolved = SystemCleanup.resolvePath(target);
                            if (size == 0 && resolved != null && Files.exists(resolved)) {
                                needsElevationList.add(resolved);
                            }
                        }, executor))
                        .toArray(java.util.concurrent.CompletableFuture[]::new);
                java.util.concurrent.CompletableFuture.allOf(futures).join();
            }
        } catch (Exception ex) {
            // Defense in depth: SystemCleanup.calculateSize() should no longer throw
            // (see walkFilesSafe), but a single bad target must never leave the section
            // stuck on "Scanning..." forever with only an unlogged console stack trace.
            Platform.runLater(() -> {
                if (generation.get() != myGen) return;
                cachesSection.getChildren().setAll(new Label("Failed to scan cleanup targets: " + ex.getMessage()));
            });
            return;
        }

        Platform.runLater(() -> {
            if (generation.get() != myGen) return;
            cachesReclaimable = sizes.values().stream().mapToLong(Long::longValue).sum();
            updateTotalReclaimableHeader();
            renderCaches(sizes, needsElevationList);
        });
    }

    private void renderCaches(Map<SystemCleanup.Target, Long> sizes, List<Path> needsElevationList) {
        cachesSection.getChildren().clear();

        var sectionHeader = new Label("Package & System Caches \u2014 total: (calculating...)");
        sectionHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        cachesSection.getChildren().add(sectionHeader);

        var grid = buildCleanupGrid(cleanupTargets, sizes, sectionHeader, needsElevationList);
        cachesSection.getChildren().add(grid);
        maybeShowElevateButton(needsElevationList, grid, sectionHeader);
    }

    private javafx.scene.layout.GridPane buildCleanupGrid(
            List<SystemCleanup.Target> targets,
            Map<SystemCleanup.Target, Long> sizes,
            Label header, List<Path> needsElevationList) {

        var grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(5);
        setColumnConstraints(grid, col(200, 150, false), col(90, 70, false), col(320, 150, true), col(190, 140, false));

        long grandTotal = 0;
        int row = 0;

        for (var target : targets) {
            long size = sizes.getOrDefault(target, 0L);
            grandTotal += size;
            var resolved = SystemCleanup.resolvePath(target);
            boolean locked = needsElevationList.contains(resolved);

            var nameLabel = new Label((locked ? "\uD83D\uDD12 " : "") + target.name() +
                    (target.isHighRisk() ? " \u26A0" : ""));
            if (target.isHighRisk()) nameLabel.setStyle("-fx-text-fill: #c0392b;");
            var sizeLabel = new Label(locked ? "locked" : SizeFormat.format(size));
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
                } catch (IOException ex) {
                    reportStatus("Cannot open: " + ex.getMessage());
                }
            });
            actions.getChildren().add(openBtn);

            if (target.customCommand() != null && !target.customCommand().isEmpty()) {
                var cmdBtn = new Button("Run");
                cmdBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6; -fx-text-fill: #2980b9;");
                cmdBtn.setOnAction(e -> confirmAndRunCommand(target));
                actions.getChildren().add(cmdBtn);
            }

            if (!target.actionOnly()) {
                var deleteBtn = new Button("Delete");
                deleteBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6; -fx-text-fill: #c0392b;");
                deleteBtn.setOnAction(e -> deleteCleanupTarget(target, resolved, size));
                actions.getChildren().add(deleteBtn);
            }

            grid.add(actions, 3, row);
            row++;
        }

        header.setText("Package & System Caches \u2014 total: " + SizeFormat.format(grandTotal));
        return grid;
    }

    private void confirmAndRunCommand(SystemCleanup.Target target) {
        var confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Run command");
        confirm.setHeaderText("Run this command on your system?");
        confirm.setContentText(target.customCommand());
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                pb.command("cmd", "/c", target.customCommand());
            } else {
                pb.command("sh", "-c", target.customCommand());
            }
            pb.inheritIO().start();
        } catch (IOException ex) {
            reportStatus("Failed to run command: " + ex.getMessage());
        }
    }

    private void deleteCleanupTarget(SystemCleanup.Target target, Path resolved, long size) {
        if (!confirmDelete("Delete " + target.name() + "?",
                (resolved != null ? resolved.toString() : "") + "\nSize: " + SizeFormat.format(size)
                        + (target.isHighRisk() ? "\n\n\u26A0 This location carries extra risk if deleted." : ""))) {
            return;
        }
        var result = SystemCleanup.delete(target);
        reportDeletionResult(result);
        refreshDevCleanup();
    }

    private void maybeShowElevateButton(List<Path> needsElevation,
                                         javafx.scene.layout.GridPane grid, Label header) {
        if (needsElevation.isEmpty()) return;

        var elevateBtn = new Button("Scan as Root");
        elevateBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold;");
        elevateBtn.setOnAction(e -> {
            elevateBtn.setDisable(true);
            elevateBtn.setText("Requesting elevated access...");
            Thread.ofVirtual().start(() -> {
                var elevation = SystemCleanup.calculateSizesViaElevation(needsElevation);
                Platform.runLater(() -> {
                    for (var entry : elevation.sizes().entrySet()) {
                        for (int i = 0; i < cleanupTargets.size(); i++) {
                            var t = cleanupTargets.get(i);
                            var r = SystemCleanup.resolvePath(t);
                            if (r != null && r.equals(entry.getKey())) {
                                var label = (Label) grid.lookup("#size-" + i);
                                if (label != null) label.setText(SizeFormat.format(entry.getValue()));
                                break;
                            }
                        }
                    }
                    elevateBtn.setText("Scan as Root");
                    elevateBtn.setDisable(false);
                    if (!elevation.success()) {
                        // Surface *why* nothing happened instead of leaving the button/labels
                        // looking like the click was silently ignored (e.g. UAC declined,
                        // policy-blocked helper, timed-out prompt hidden behind another window).
                        var alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("Elevated scan failed");
                        alert.setHeaderText("Could not complete the elevated scan");
                        alert.setContentText(elevation.error());
                        alert.showAndWait();
                    } else {
                        header.setText("Package & System Caches \u2014 total: (refresh to recalculate)");
                    }
                });
            });
        });
        cachesSection.getChildren().add(elevateBtn);
    }

    // ── Compress ─────────────────────────────────────────────────────────

    private void refreshCompress() {
        compressBox.getChildren().clear();
        if (root == null) return;

        var estimate = CompressionEstimate.estimate(root);
        compressBox.getChildren().add(new Label("Compression Potential"));
        compressBox.getChildren().add(new Label("Original: " + SizeFormat.format(estimate.originalSize())));
        compressBox.getChildren().add(new Label("Estimated after compression: " + SizeFormat.format(estimate.estimatedCompressed())));
        compressBox.getChildren().add(new Label("Could save: " + SizeFormat.format(estimate.savings()) +
                " (" + String.format(java.util.Locale.ROOT, "%.0f%%", (1 - estimate.ratio()) * 100) + ")"));
        compressBox.getChildren().add(new Label("Strategy: " + estimate.strategy()));
        compressBox.getChildren().add(new Label(""));

        var byCat = new java.util.HashMap<FileCategory, long[]>();
        var files = FileAnalysis.flattenFiles(root);
        for (var f : files) {
            var cat = FileCategory.forFile(f.getName());
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
            grid.add(new Label(SizeFormat.format(orig)), 1, row);
            grid.add(new Label(SizeFormat.format(compressed)), 2, row);
            grid.add(new Label(SizeFormat.format(save)), 3, row);
            row++;
        }
        compressBox.getChildren().add(grid);
    }

    // ── Groups ───────────────────────────────────────────────────────────

    private void refreshGroups() {
        groupBox.getChildren().clear();
        if (root == null) return;
        FileNode r = root;
        long myGen = generation.get();
        String mode = groupMode.getValue();

        groupBox.getChildren().add(new Label("Grouping..."));

        Thread.ofVirtual().start(() -> {
            var groups = switch (mode) {
                case "Age" -> FileGrouper.byAge(r);
                case "Owner" -> FileGrouper.byOwner(r); // I/O per file — must stay off the FX thread
                default -> FileGrouper.byFileType(r);
            };
            Platform.runLater(() -> {
                if (generation.get() != myGen) return;
                renderGroups(mode, groups);
            });
        });
    }

    private void renderGroups(String mode, List<FileGrouper.Group> groups) {
        groupBox.getChildren().clear();
        long totalSize = groups.stream().mapToLong(FileGrouper.Group::totalSize).sum();
        var header = new Label(mode + " grouping \u2014 " + groups.size() + " groups, " + SizeFormat.format(totalSize));
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
            grid.add(new Label(SizeFormat.format(g.totalSize())), 2, row);
            grid.add(new Label(String.format(java.util.Locale.ROOT, "%.1f%%", pct)), 3, row);
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

    // ── Snapshots ────────────────────────────────────────────────────────

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
                reportStatus("Snapshot saved: " + nameField.getText());
                refreshSnapshots();
            } catch (IOException ex) {
                reportStatus("Failed to save: " + ex.getMessage());
            }
        });

        snapshotBox.getChildren().addAll(saveLabel, new HBox(10, nameField, saveBtn));

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
                reportStatus("Compare failed: " + ex.getMessage());
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
            } catch (IOException ex) {
                reportStatus("Failed to delete snapshot: " + ex.getMessage());
            }
        });
        snapshotBox.getChildren().add(deleteBtn);
    }

    private void showDiff(SnapshotManager.SnapshotDiff diff) {
        var dialog = new Dialog<Void>();
        dialog.setTitle("Snapshot Comparison");
        dialog.getDialogPane().setPrefSize(600, 400);

        var summary = SnapshotManager.summarize(diff);
        var content = new VBox(8);
        content.setPadding(new Insets(10));
        content.getChildren().add(new Label("Changes since previous snapshot:"));
        content.getChildren().add(new Label(""));

        if (!diff.hasChanges()) {
            content.getChildren().add(new Label("No changes detected."));
        } else {
            if (summary.addedCount() > 0) content.getChildren().add(new Label(
                    "Added: " + summary.addedCount() + " files (" + SizeFormat.format(summary.totalAdded()) + ")"));
            if (summary.removedCount() > 0) content.getChildren().add(new Label(
                    "Removed: " + summary.removedCount() + " files (" + SizeFormat.format(summary.totalRemoved()) + ")"));
            if (summary.grownCount() > 0) content.getChildren().add(new Label(
                    "Grown: " + summary.grownCount() + " files (+" + SizeFormat.format(summary.totalGrown()) + ")"));
            if (summary.shrunkCount() > 0) content.getChildren().add(new Label(
                    "Shrunk: " + summary.shrunkCount() + " files (-" + SizeFormat.format(summary.totalShrunk()) + ")"));
            content.getChildren().add(new Label("Net change: " + SizeFormat.format(summary.netChange())));
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

    // ── Shared helpers ───────────────────────────────────────────────────

    /**
     * Fixes column widths on the cleanup/artifact/Docker grids so the actions column
     * (Open/Run/Delete/Clean buttons) always keeps its minimum width instead of being
     * squeezed down to nothing when the panel is narrow — only the designated "grow" column
     * (description/path, or nothing in the Docker grid) absorbs any extra or missing width.
     * Without this, {@code GridPane} shrinks every column's content roughly proportionally
     * to fit the available width, which is exactly what made the button column unreadable.
     */
    private static void setColumnConstraints(javafx.scene.layout.GridPane grid,
                                              javafx.scene.layout.ColumnConstraints... constraints) {
        grid.getColumnConstraints().setAll(constraints);
    }

    private static javafx.scene.layout.ColumnConstraints col(double prefWidth, double minWidth, boolean grow) {
        var c = new javafx.scene.layout.ColumnConstraints();
        c.setPrefWidth(prefWidth);
        c.setMinWidth(minWidth);
        c.setHgrow(grow ? javafx.scene.layout.Priority.ALWAYS : javafx.scene.layout.Priority.NEVER);
        return c;
    }

    private boolean confirmDelete(String header, String details) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Deletion");
        alert.setHeaderText(header);
        if (details != null) alert.setContentText(details);
        return alert.showAndWait().orElse(null) == ButtonType.OK;
    }

    private void reportDeletionResult(DeletionService.DeletionResult result) {
        if (result.allSucceeded()) {
            reportStatus("Deleted " + result.deleted().size() + " item(s)"
                    + (Settings.get().moveToTrash ? " (moved to trash)" : ""));
            return;
        }
        var alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Some items were not deleted");
        alert.setHeaderText(result.deleted().size() + " deleted, " + result.failureCount() + " failed");
        var details = result.errors().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        alert.setContentText(details);
        alert.showAndWait();
    }
}
