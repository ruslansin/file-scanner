# File Scanner

Disk space analyzer with treemap & rings visualization built with JavaFX. Cross-platform GNOME Baobab clone.

## Stack

- Java 21 — Virtual Threads for parallel scanning
- JavaFX 21 — GUI (Canvas, TreeView, SplitPane)
- Maven — build and dependency management
- Gson — JSON for configs, snapshots, CLI
- PDFBox — PDF report generation
- JUnit 5 + Mockito — testing
- Cross-platform — `java.nio.file.Path`, runs on Windows/Linux/macOS

## Quick Start

```bash
# Build
mvn compile

# GUI
mvn javafx:run

# CLI (no JavaFX needed)
java -cp target/classes:$HOME/.m2/repository/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar \
    by.snql.filescanner.ui.FileScannerCli --scan ~ --all

# Tests
mvn test
```

## Project Structure

```
src/main/java/by/snql/filescanner/
├── Main.java              # Entry point (JavaFX Application)
├── model/
│   └── FileNode.java      # Tree node: path, name, size, children, symlink/hardlink/buildArtifact flags
├── scanner/
│   └── FileScanner.java   # Recursive FS walk on Virtual Threads, link detection, ScanProgress
└── ui/
    ├── MainWindow.java       # Orchestrator: toolbar, tree, charts, drag&drop, history, glob/regex filter
    ├── TreemapChart.java     # Canvas treemap (squarified), file-type colors via FileTypeCategory
    ├── RingsChart.java       # Canvas sunburst rings, file-type colors
    ├── TreemapLayout.java    # Layout algorithm (isolated from JavaFX, testable)
    ├── FileTypeCategory.java # Enum: IMAGE/VIDEO/AUDIO/DOC/ARCHIVE/CODE/EXE/FONT/DISK/OTHER with colors
    ├── ProjectType.java      # Enum: project detection by config files for build artifact tagging
    ├── BottomTabs.java       # TabPane: 10 analysis tabs
    ├── FileAnalysis.java     # Pure utility: flatten, largest, types, SHA-256 dupes, old files, empty dirs
    ├── FileGrouper.java      # Alternative grouping: by file type/age/owner
    ├── CompressionEstimate.java  # Heuristic compression ratio per file category
    ├── SnapshotManager.java  # Save/load/compare disk usage snapshots with diff view
    ├── FileScannerCli.java   # Headless CLI: --scan, --largest, --duplicates, --types, --empty, --json
    ├── ExportUtils.java      # CSV/JSON/HTML export
    ├── SystemCleanup.java    # OS + dev cache scanning, custom commands, build artifacts
    └── Settings.java         # Persistent settings in ~/.filescanner/settings.json

src/main/resources/
└── cleanup-targets.json      # Windows/Linux/macOS cache targets + developer tools + build artifacts
```

## Key Features

### Visualization
- **Treemap & Rings** — Two chart modes, toggle in toolbar. Treemap: squarified layout. Rings: concentric sunburst arcs.
- **File type coloring** — 10 color categories: images (red), video (orange), audio (purple), documents (blue), archives (brown), code (green), executables (teal), fonts (maroon), disk images (purple), other (gray)
- **Real-time scan progress** — Files found and total size update live in status bar during scan

### File Management
- **Delete** — Right-click or Delete key, multi-select (Ctrl+Click), confirmation with total size
- **Open in File Manager** — Right-click → open folder in system file manager
- **Open File** — Right-click → launch file in default app
- **Context menu** — Export (CSV/JSON/HTML), Delete, Open

### Analysis Tabs (10 total)
| Tab | Description |
|-----|-------------|
| Largest Files | Top-100 files by size, sortable table |
| File Types | Breakdown by extension with counts and totals |
| Duplicates | SHA-256 duplicate detection, select and delete |
| Empty Dirs | All empty directories in the scanned tree |
| Old Files | Files not modified for 30/90/180/365 days |
| Cleanup | System caches + developer tool caches, parallel scan, [Open]/[Delete]/[Run] |
| Project Cleanup | Build artifact detection: target/, node_modules/, build/, __pycache__, .next/ etc. |
| Compress | Estimated compression savings per file category (heuristic ratios) |
| Groups | Alternative grouping: by file type, age bucket, or file owner |
| Snapshots | Save scan snapshot, compare with previous (added/removed/grown/shrunk) |

### Search & Filter
- **Glob patterns** — `*.log`, `node_modules`, `*.java`
- **Regex** — `regex:pom\.xml`
- Filters both tree and treemap/rings views simultaneously

### Productivity
- **Drag & drop** — Drop a folder onto the window to scan
- **History** — Combobox with last 20 scanned folders
- **Hidden files** — Checkbox toggle
- **Dark mode** — Checkbox toggle
- **Sort** — By size, name, or date
- **Ctrl+F** — Go to search field

### Cleanup
- **System caches** — Windows Update, Temp, Prefetch, APT, Snap, Trash, Journal, Homebrew, etc.
- **Developer caches** — Maven, Gradle, npm, pip, Cargo, Go, NuGet, Yarn, Composer, RubyGems, Docker, Conda, etc.
- **Custom commands** — `customCommand` field in JSON targets, executes `sh -c` (or `cmd /c` on Windows)
- **Elevated access** — `pkexec` (Linux), `osascript` (macOS), UAC (Windows) for locked paths
- **Parallel scanning** — All targets scanned concurrently via Virtual Threads

### Link Detection
- **Symlinks** — Detected, size=0, shown with ↗ marker
- **Hardlinks** — Detected by inode, size=0, shown with ⫘ marker

### Build Artifacts
- **13 project types** detected: Maven, Gradle, Node.js, Python, Rust, Go, .NET, PHP, CMake, Make, etc.
- Artifacts highlighted with 🧹 icon and orange bold text in tree
- Right-click → "Delete Build Artifact"
- Configurable scan roots and depth in Settings

## CLI Mode

```
Usage: java -cp ... by.snql.filescanner.ui.FileScannerCli [options]

Options:
  --scan, -s <dir>    Directory to scan (required)
  --all, -a           Show all reports
  --largest, -l       Show largest files
  --duplicates, -d    Show duplicate files
  --types, -t         Show file type breakdown
  --empty, -e         Show empty directories
  --limit, -n <N>     Max results for largest files (default 50)
  --json, -j          Output as JSON instead of text
  --help, -h          Show help

Examples:
  java -cp ... FileScannerCli --scan ~ --all
  java -cp ... FileScannerCli -s ~ -a -j > report.json
```

Only requires `gson.jar` — no JavaFX needed.

## Settings

`~/.filescanner/settings.json`:

```json
{
  "duplicateSHA256": false,
  "scanHidden": false,
  "darkMode": false,
  "defaultSort": "size",
  "scanRoots": ["~/dev", "~/projects"],
  "projectScanEnabled": true,
  "projectScanDepth": 5
}
```

## Architecture

### Single-pass scanner
Uses `AtomicLong` discovered/processed counters — no preliminary `countAll` walk. Progress is real-time.
`ScanProgress` record emitted periodically: filesDiscovered, filesProcessed, totalSizeSoFar.

### Duplicate detection
Size-only matching by default. SHA-256 via `DigestInputStream` with 8KB buffer when enabled — no `readAllBytes`, safe for large files.

### Snapshot comparison
Saves flat path→size maps to `~/.filescanner/snapshots/`. Compare shows: added, removed, grown, shrunk with net change summary.

### Test coverage

| Class | Count | Covers |
|-------|-------|--------|
| `FileNodeTest` | 11 | constructor, addChild, sortChildren, isLeaf, setSize |
| `FileScannerTest` | 11 | all scan scenarios, cancel, progress, symlinks, concurrent scans |
| `FormatSizeTest` | 14 | B/KB/MB/GB/TB boundaries, zero/negative, parameterized |
| `TreemapLayoutTest` | 19 | null/zero sizes, layout, non-overlap, squarify, depth, descendants |
| `SystemCleanupTest` | 10 | resolvePath, calculateSize, targets per-OS, elevation API |

65 tests total, all passing.
