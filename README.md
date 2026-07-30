# File Scanner

Disk space analyzer with treemap visualization built with JavaFX.

Cross-platform (Windows, Linux, macOS) — GNOME Baobab clone.

## Stack

- Java 21 — Virtual Threads for parallel scanning
- JavaFX 21 — GUI (Canvas, TreeView, SplitPane)
- Maven — build and dependency management
- JUnit 5 + Mockito — testing
- Cross-platform — `java.nio.file.Path`, runs on Windows/Linux/macOS

## Quick Start

```bash
# Build
mvn compile

# Run
mvn javafx:run

# Tests
mvn test
```

## Project Structure

```
src/main/java/by/snql/filescanner/
├── Main.java              # Entry point (JavaFX Application)
├── model/
│   └── FileNode.java      # Tree node: path, name, size, children
├── scanner/
│   └── FileScanner.java   # Recursive FS walk on Virtual Threads
└── ui/
    ├── MainWindow.java    # Window: toolbar, tree, progress, context menu
    ├── TreemapChart.java  # Canvas treemap visualization
    └── TreemapLayout.java # Treemap layout algorithm (squarified)

src/main/resources/styles/
└── main.css               # JavaFX styles

src/test/java/by/snql/filescanner/
├── model/
│   └── FileNodeTest.java
├── scanner/
│   └── FileScannerTest.java
└── ui/
    ├── FormatSizeTest.java
    └── TreemapLayoutTest.java
```

## Key Features

- **Treemap** — Color-coded visualization: directories in grayscale, files in 8-color palette. Nested depth affects brightness.
- **Folder tree** — Hierarchical list on the left, sorted by size
- **Navigation** — Click a rectangle to drill into a folder; selection syncs with the tree
- **Deletion** — Right-click file/folder → "Delete", or press Delete key. Confirmation with size, recursive deletion, auto-rescan
- **Progress** — Two-pass scanning: count directories → build tree
- **Cancel** — Interrupt scanning via `volatile` flag
- **Virtual Threads** — Java 21 lightweight threads for non-blocking I/O
- **Size formatting** — B, KB, MB, GB, TB

## Architecture

### FileScanner

Two-pass algorithm:
1. `countAll` — recursively counts directories for progress bar
2. `buildTree` — builds `FileNode` tree, reporting progress

Both passes check `volatile boolean cancelled` for cancellation.

### TreemapLayout (algorithm)

Isolated from JavaFX, independently testable:
- `compute(node, w, h)` — recursive layout: root gets the full rectangle, children get proportional areas via `squarify`
- `squarify` — splits a rectangle among children proportional to their size, minimum 4px
- `displayDepth` — depth for color scheme, cascade of single-child dirs counts as one level
- `countDescendants` — total node count (used for array allocation)

### MainWindow

- `formatSize(bytes)` — static formatter: `0 B`, `1.0 KB`, `1.0 MB`, …, `1.0 TB`

### Tests

| Class | Coverage |
|-------|----------|
| `FileNodeTest` | constructor, addChild (size accumulation), sortChildren (recursive), isLeaf, setSize |
| `FileScannerTest` | empty dir, dir with files, nested dirs, progress, nonexistent path, cancel, correct sizes, symlinks, concurrent scans, post-scan sort |
| `FormatSizeTest` | zero/negative, B/KB/MB/GB/TB boundaries, parameterized tests |
| `TreemapLayoutTest` | null/zero sizes, leaf nodes, child layout, nesting, area proportionality, non-overlap, countDescendants, displayDepth, squarify |
