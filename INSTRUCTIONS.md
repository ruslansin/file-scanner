# File Scanner — Instructions

Disk space analyzer with treemap and rings visualization. Cross-platform GNOME Baobab clone.

## Quick Start

```bash
git clone https://github.com/ruslansin/file-scanner.git
cd file-scanner

mvn compile
mvn javafx:run     # GUI
mvn test           # unit tests
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
- **History** — last 20 scans, persisted across restarts
- **⚙** — settings dialog

### Visualization

- **Treemap** — squarified rectangles, area = file size. Click a folder to zoom in.
- **Rings** — concentric sunburst arcs. Each ring is a nesting level, drawn as a proper annulus
  (a child ring never paints over its ancestor).
- **Colors** — files colored by type: code (green), images (red), video (orange), etc.
- **Breadcrumb bar + Up button** above each chart lets you navigate back out after drilling in.

### File tree

Shows both files and folders (kept in sync during a live scan, matched by path so expansion state
survives incremental updates).

- **Multi-select** — Ctrl+Click, Delete to remove
- **Right-click** — Open in File Manager, Open File, Export (CSV/JSON/HTML/PDF), Delete
- **↗** — symlink (never followed, even into a directory), **⫘** — hardlink/directory cycle, **🧹** — build artifact

### Deletion

By default, deleted items are moved to the OS trash/recycle bin, not permanently removed. You can
opt into permanent deletion in Settings ("Move deleted items to Trash/Recycle Bin"). Whichever mode
you choose, every deletion shows you exactly what succeeded and what failed (with the reason) —
nothing is silently swallowed.

### Analysis tabs (10 total)

| Tab | Contents |
|-----|----------|
| Largest Files | Top-100 files by size |
| File Types | Breakdown by extension |
| Duplicates | SHA-256 duplicate groups (optional, off by default) |
| Empty Dirs | Empty directories |
| Old Files | Files older than N days |
| Cleanup | System + dev tool caches, custom commands (confirmed before running) |
| Project Cleanup | Build artifacts: target/, node_modules/, build/, etc. |
| Compress | Compression savings estimate |
| Groups | Grouped by type / age / owner |
| Snapshots | Save / compare disk snapshots |

Only the currently-visible tab is (re)computed on a scan; switching tabs computes that tab on
demand. This keeps large scans responsive instead of recomputing all 10 tabs at once.

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
  "projectScanDepth": 5,
  "moveToTrash": true,
  "recentPaths": []
}
```

`moveToTrash` controls whether deletions (tree, duplicates, cleanup targets, build artifacts) go to
the OS trash/recycle bin (`true`, default) or delete permanently (`false`). If the OS doesn't support
moving to trash, deletion fails loudly with an explanation rather than silently deleting permanently.

## Custom cleanup targets

File `~/.filescanner/cleanup-targets.json`:

```json
{
  "linux": [
    { "name": "My temp files", "path": "/home/user/tmp", "description": "Temporary junk", "contentsOnly": true },
    { "name": "Docker prune", "path": "/var/lib/docker", "customCommand": "docker system prune -af", "risk": "high" }
  ]
}
```

Fields:

| Field | Meaning |
|-------|---------|
| `name` | required |
| `path` / `linuxPath` / `winPath` / `macPath` | location, with `$HOME`, `%TEMP%`, `%LOCALAPPDATA%`, `%APPDATA%`, `%WINDIR%`, `$XDG_CACHE_HOME` substitution |
| `description` | shown in the Cleanup tab |
| `contentsOnly` | delete the folder's *contents*, keep the folder itself (needed for locations like `%TEMP%` that must keep existing) |
| `daysOld` | only files older than N days count towards size/deletion |
| `extension` | only files with this suffix (e.g. `.log`) count towards size/deletion |
| `filesOnly` | the last path segment is a glob pattern matching files directly inside its parent (e.g. `thumbcache_*.db`) |
| `risk` | `"low"` (default) or `"high"` — high-risk targets are flagged with ⚠ and get an extra warning in the delete confirmation |
| `customCommand` | when set, a **Run** button appears in the Cleanup tab; the exact command is shown to you in a confirmation dialog before it runs (`sh -c` on Linux/macOS, `cmd /c` on Windows) |

## Snapshots

Stored in `~/.filescanner/snapshots/` as JSON path→size maps (keyed by the real, platform-native
file path — comparisons work correctly regardless of `/` vs `\`).

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
│   └── FileScanner.java   # Recursive FS walk on Virtual Threads; each scan() call is isolated
├── config/
│   ├── Settings.java       # Persistent settings
│   └── CacheManager.java   # Persists the last scan for instant startup
├── core/                   # No JavaFX imports — usable/testable standalone
│   ├── project/ProjectType.java
│   ├── analysis/{FileAnalysis,FileGrouper,FileCategory,CompressionEstimate}.java
│   ├── cleanup/{SystemCleanup,DeletionService}.java
│   ├── export/{ExportUtils,PdfReport,SnapshotManager}.java
│   └── util/SizeFormat.java
└── ui/
    ├── MainWindow.java        # Main window: toolbar, tree, charts, drag&drop
    ├── TreemapChart.java      # Canvas treemap (squarified)
    ├── RingsChart.java        # Canvas rings (sunburst, proper annulus rendering)
    ├── TreemapLayout.java     # Layout algorithm (testable independently)
    ├── FileTypeCategory.java  # Maps core.analysis.FileCategory to JavaFX colors
    └── BottomTabs.java        # 10 analysis tabs, lazily refreshed
```

## Adding a new project type

1. `core/project/ProjectType.java` — add enum value with configFile and artifacts
2. `cleanup-targets.json` — add artifact name to `buildArtifacts`

## Adding a new cleanup target

Edit `~/.filescanner/cleanup-targets.json` or `src/main/resources/cleanup-targets.json`. Prefer
`contentsOnly: true` for any location that must continue to exist (temp dirs, caches actively used
by a running process), and mark anything with real blast radius as `"risk": "high"`.

## Building a fat JAR

```bash
mvn package
java -jar target/file-scanner-1.0.0.jar
```
