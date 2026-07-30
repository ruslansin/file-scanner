# File Scanner — Instructions

Disk space analyzer with treemap and rings visualization. Cross-platform GNOME Baobab clone.

## Quick Start

```bash
git clone https://github.com/ruslansin/file-scanner.git
cd file-scanner

mvn compile
mvn javafx:run     # GUI
mvn test            # 65 tests
```

## CLI

```bash
mvn compile

java -cp "target/classes:$(find ~/.m2 -name 'gson-*.jar' | head -1):$(find ~/.m2 -path '*/pdfbox/*.jar' | tr '\n' ':')$(find ~/.m2 -name 'commons-logging-1.3*.jar' | head -1)" \
    by.snql.filescanner.ui.FileScannerCli --scan ~ --all
```

### CLI flags

| Flag | Description |
|------|-------------|
| `--scan, -s <dir>` | Directory to scan (**required**) |
| `--all, -a` | All report sections |
| `--largest, -l` | Largest files |
| `--types, -t` | File type breakdown |
| `--duplicates, -d` | Duplicate files |
| `--empty, -e` | Empty directories |
| `--compress, -c` | Compression estimate |
| `--groups, -g` | Group by type / age / owner |
| `--limit, -n <N>` | Max files in top list (default 50) |
| `--json, -j` | JSON output instead of text |
| `--pdf <file>` | Generate PDF report |
| `--help, -h` | Show help |

Examples:

```bash
java ... FileScannerCli -s ~/dev/myproject
java ... FileScannerCli -s ~ -a -j > report.json
java ... FileScannerCli -s ~ --pdf disk-report.pdf
java ... FileScannerCli -s /home -c -g
```

## GUI

### Toolbar

- **Scan Folder** — choose directory to scan
- **Cancel** — stops active scan
- **Home** — quick-scan home directory
- **Refresh** — re-scan current directory
- **Show hidden** — toggle hidden files
- **Dark** — toggle dark mode
- **Sort by** — size / name / date
- **Treemap / Rings** — switch visualization mode
- **Filter** — text, glob (`*.java`), or regex (`regex:pom\.xml`)
- **History** — last 20 scans
- **⚙** — settings dialog

### Visualization

- **Treemap** — squarified rectangles, area = file size. Click a folder to zoom in.
- **Rings** — concentric sunburst arcs. Each ring is a nesting level.
- **Colors** — files colored by type: code (green), images (red), video (orange), etc.

### File tree

- **Multi-select** — Ctrl+Click, Delete to remove
- **Right-click** — Open in File Manager, Open File, Export, Delete
- **↗** — symlink, **⫘** — hardlink, **🧹** — build artifact

### Analysis tabs (10 total)

| Tab | Contents |
|-----|----------|
| Largest Files | Top-100 files by size |
| File Types | Breakdown by extension |
| Duplicates | SHA-256 duplicate groups (optional) |
| Empty Dirs | Empty directories |
| Old Files | Files older than N days |
| Cleanup | System + dev tool caches, custom commands |
| Project Cleanup | Build artifacts: target/, node_modules/, build/, etc. |
| Compress | Compression savings estimate |
| Groups | Grouped by type / age / owner |
| Snapshots | Save / compare disk snapshots |

### Drag & Drop

Drop a folder onto the window to start scanning.

## Settings

File `~/.filescanner/settings.json`:

```json
{
  "duplicateSHA256": false,
  "scanHidden": false,
  "darkMode": false,
  "defaultSort": "size",
  "scanRoots": ["~/dev"],
  "projectScanEnabled": true,
  "projectScanDepth": 5
}
```

## Custom cleanup targets

File `~/.filescanner/cleanup-targets.json`:

```json
{
  "linux": [
    { "name": "My temp files", "path": "/home/user/tmp", "description": "Temporary junk" },
    { "name": "Docker prune", "path": "/var/lib/docker", "customCommand": "docker system prune -af" }
  ]
}
```

Fields: `name` (required), `path` or `linuxPath`/`winPath`/`macPath`, `description`, `customCommand`.

When `customCommand` is set, a **Run** button appears in the Cleanup tab executing `sh -c "command"`.

## Snapshots

Stored in `~/.filescanner/snapshots/` as JSON path→size maps.

- **Save** — save current scan
- **Compare** — diff against previous (added / removed / grown / shrunk)
- **Delete** — remove snapshot

## Architecture

```
by.snql.filescanner/
├── Main.java              # JavaFX entry point
├── model/
│   └── FileNode.java      # Tree node: path, name, size, children, flags
├── scanner/
│   └── FileScanner.java   # Recursive FS walk on Virtual Threads, ScanProgress
└── ui/
    ├── MainWindow.java        # Main window: toolbar, tree, charts, drag&drop
    ├── TreemapChart.java      # Canvas treemap (squarified)
    ├── RingsChart.java        # Canvas rings (sunburst)
    ├── TreemapLayout.java     # Layout algorithm (testable independently)
    ├── FileTypeCategory.java  # File category colors
    ├── ProjectType.java       # Project type detection via config files
    ├── BottomTabs.java        # 10 analysis tabs
    ├── FileAnalysis.java      # Utilities: flatten, largest, types, dupes, old, empty
    ├── FileGrouper.java       # Group by type / age / owner
    ├── CompressionEstimate.java # Heuristic compression ratios
    ├── SnapshotManager.java   # Save / load / compare snapshots
    ├── PdfReport.java         # PDF report with tables
    ├── FileScannerCli.java    # CLI: scan, reports, JSON, PDF
    ├── ExportUtils.java       # CSV / JSON / HTML export
    ├── SystemCleanup.java     # System caches + commands, elevation
    └── Settings.java          # Persistent settings
```

## Adding a new project type

1. `ProjectType.java` — add enum value with configFiles and artifacts
2. `cleanup-targets.json` — add artifact name to `buildArtifacts`

## Adding a new cleanup target

Edit `~/.filescanner/cleanup-targets.json` or `src/main/resources/cleanup-targets.json`.

## Building a fat JAR

```bash
mvn package
java -jar target/file-scanner-1.0.0.jar
```
