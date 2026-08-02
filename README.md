# File Scanner

Disk space analyzer with treemap & rings visualization built with JavaFX. Cross-platform GNOME Baobab clone.

## Stack

- Java 21 — Virtual Threads for parallel scanning
- JavaFX 21 — GUI (Canvas, TreeView, SplitPane)
- Maven — build and dependency management
- Gson — JSON for configs, snapshots
- PDFBox — PDF report generation
- JUnit 5 + Mockito — testing
- Cross-platform — `java.nio.file.Path`, runs on Windows/Linux/macOS

## Quick Start

```bash
# Build
mvn compile

# Run the GUI
mvn javafx:run

# Tests
mvn test
```

## Project Structure

```
src/main/java/by/snql/filescanner/
├── Main.java                 # Entry point (JavaFX Application)
├── model/
│   └── FileNode.java         # Tree node: path, name, size, children, symlink/hardlink/buildArtifact flags
├── scanner/
│   └── FileScanner.java      # Recursive FS walk on Virtual Threads, per-scan isolated Session, link detection
├── config/
│   ├── Settings.java         # Persistent settings (~/.filescanner/settings.json)
│   └── CacheManager.java     # Persists the last completed scan for instant startup
└── core/                     # Platform/UI-independent logic — no JavaFX imports
    ├── project/
    │   └── ProjectType.java  # Project type detection by config file (pom.xml, package.json, Cargo.toml...)
    ├── analysis/
    │   ├── FileAnalysis.java       # flatten, largest, types, SHA-256 dupes, old files, empty dirs
    │   ├── FileGrouper.java        # Alternative grouping: by file type, age bucket, or file owner
    │   ├── FileCategory.java       # File extension -> category (image/video/code/...), no colors
    │   └── CompressionEstimate.java # Heuristic compression ratio estimation per file category
    ├── cleanup/
    │   ├── SystemCleanup.java      # OS-specific cache targets from cleanup-targets.json, elevation
    │   └── DeletionService.java    # Centralized, honest deletion (trash by default, reports failures)
    ├── export/
    │   ├── ExportUtils.java        # Streaming CSV/JSON/HTML export
    │   ├── PdfReport.java          # PDF report (available from the GUI's Export menu)
    │   └── SnapshotManager.java    # Save/load/compare disk usage snapshots with diff view
    └── util/
        └── SizeFormat.java         # Locale-independent "1.5 MB"-style formatting
└── ui/
    ├── MainWindow.java       # Orchestrator: toolbar, tree, charts, drag&drop, history, glob/regex filter
    ├── TreemapChart.java     # Canvas treemap (squarified layout), file-type coloring
    ├── RingsChart.java       # Canvas sunburst rings, file-type coloring
    ├── TreemapLayout.java    # Layout algorithm (isolated from JavaFX, testable)
    ├── FileTypeCategory.java # Maps core.analysis.FileCategory to JavaFX colors
    └── BottomTabs.java       # TabPane: Largest, Types, Duplicates, Empty Dirs, Old Files, Cleanup,
                               #   Project Cleanup, Compress, Groups, Snapshots — lazily refreshed per tab

src/main/resources/
└── cleanup-targets.json      # Windows/Linux/macOS cache targets + developer tools + build artifacts
```

## Key Features

### Visualization
- **Treemap & Rings** — Two chart modes, toggle in toolbar. Treemap: squarified layout. Rings: concentric sunburst (proper annulus rendering — rings never overdraw their ancestors).
- **Drill-down navigation** — Click into a folder in either chart; a breadcrumb bar and an "Up" button let you navigate back out.
- **File type coloring** — 10 color categories: images (red), video (orange), audio (purple), documents (blue), archives (brown), code (green), executables (teal), fonts (maroon), disk images (purple), other (gray)
- **Real-time scan progress** — Files found and total size update live in status bar during scan

### File Management
- **Delete** — Right-click or Delete key, multi-select (Ctrl+Click), confirmation with total size
- **Trash by default** — Deleted items go to the OS trash/recycle bin unless permanent deletion is explicitly enabled in Settings; every deletion reports exactly what succeeded and what failed (never silently swallowed)
- **Open in File Manager** — Right-click → open folder in system file manager
- **Open File** — Right-click → launch file in default app
- **Context menu** — Export (CSV/JSON/HTML/PDF), Delete, Open

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

Tabs refresh lazily — only the tab you're viewing is (re)computed; the others recompute on demand
when you switch to them, and a stale background computation from a previous scan can never
overwrite the current one.

### Search & Filter
- **Glob patterns** — `*.log`, `node_modules`, `*.java`
- **Regex** — `regex:pom\.xml`
- Filters both tree and treemap/rings views simultaneously

### Productivity
- **Drag & drop** — Drop a folder onto the window to scan
- **History** — Combobox with last 20 scanned folders, persisted across restarts
- **Hidden files** — Checkbox toggle
- **Dark mode** — Checkbox toggle (separate stylesheet, not inline styles)
- **Sort** — By size, name, or date
- **Ctrl+F** — Go to search field

### Cleanup
- **System caches** — Windows Update, Temp, Prefetch alternatives, APT, Snap, Trash, developer caches, etc.
  High-risk / destructive locations (installer state, prefetch, recycle bin internals, whole log/tmp trees)
  have been removed from the defaults; the remaining risky-but-useful ones are marked "high risk" and
  require an extra confirmation, and support `contentsOnly` (clear a folder's contents but keep the
  folder itself), `daysOld`, and `extension` filters so cleanup only touches what it says it touches.
- **Developer caches** — Maven, Gradle, npm, pip, Cargo, Go, NuGet, Yarn, Composer, RubyGems, Docker, Conda, etc.
- **Custom commands** — `customCommand` field in JSON targets; always shown to you and confirmed before running (`sh -c` / `cmd /c`)
- **Elevated access** — `pkexec` (Linux), `osascript` (macOS), UAC (Windows) for locked paths; helper scripts are written to `~/.filescanner/run/` with random per-run names and cleaned up afterwards
- **Parallel scanning** — All targets scanned concurrently via Virtual Threads

### Link Detection
- **Symlinks** — Never followed (even when pointing at a directory), size=0, shown with ↗ marker
- **Hardlinks** — Detected by inode, size=0, shown with ⫘ marker
- **Directory cycles** — junctions/bind-mount loops are detected and stopped, not walked forever

### Build Artifacts
- **13 project types** detected: Maven, Gradle, Node.js, Python, Rust, Go, .NET, PHP, CMake, Make, etc.
- Artifacts highlighted with 🧹 icon and orange bold text in tree
- Right-click → "Delete Build Artifact"
- Configurable scan roots and depth in Settings

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
  "projectScanDepth": 5,
  "moveToTrash": true
}
```

## Architecture

### Layering
- `model` — the `FileNode` tree, no I/O.
- `scanner` — walks the filesystem; depends only on `model` and `core.project` (not on `config`/`ui`), so it
  can be scanned/tested without any UI or global settings singleton in the loop.
- `core.*` — all business logic (analysis, cleanup planning, export, snapshots) with **no JavaFX imports**,
  so it's usable from a script or a future non-GUI front-end and is trivially unit-testable.
- `config` — persistent settings/cache, JSON on disk.
- `ui` — JavaFX presentation only.

### Single-pass scanner
Each call to `FileScanner.scan()` runs in its own isolated session — no shared mutable state between
concurrent or sequential scans on the same `FileScanner` instance. Progress is real-time; a scan in
progress hands off periodic tree snapshots (deep-copied, so the UI thread never touches a tree the
scanner thread is still mutating) to the caller.

### Duplicate detection
Size-only pre-filtering, then SHA-256 via `DigestInputStream` with an 8KB buffer when enabled —
no `readAllBytes`, safe for large files. Unreadable files are excluded rather than treated as a
fake, never-colliding "hash".

### Deletion
All deletion goes through `DeletionService`: moves to the OS trash/recycle bin by default, reports
exactly which paths succeeded and which failed (with the reason) instead of swallowing errors.

### Snapshot comparison
Saves flat path→size maps (keyed by the real filesystem path, not a reconstructed string — works
correctly on both `/` and `\`-separated platforms) to `~/.filescanner/snapshots/`. Compare shows:
added, removed, grown, shrunk with a net change summary.

### Test coverage

| Class | Covers |
|-------|--------|
| `FileNodeTest` | constructor, addChild/attachChild (no double-counting), copyOf, sortChildren, isLeaf, setSize |
| `FileScannerTest` | all scan scenarios, cancel, progress, symlinks (not followed), concurrent scans are isolated |
| `SizeFormatTest` | B/KB/MB/GB/TB boundaries, zero/negative, locale independence, parameterized |
| `TreemapLayoutTest` | null/zero sizes, layout, non-overlap, squarify, displayDepth, countDescendants |
| `SystemCleanupTest` | resolvePath, calculateSize, targets per-OS, elevation API (without ever prompting for real elevation) |

All tests pass with `mvn test` (one symlink test is skipped rather than failed on systems/users
without permission to create symlinks, e.g. Windows without Developer Mode).
