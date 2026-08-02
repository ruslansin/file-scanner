# AGENTS.md

Disk space analyzer with treemap & rings visualization in JavaFX. Cross-platform GNOME Baobab clone.

## Build & test

- Java **21** required. No Maven wrapper — use system `mvn`.
- `mvn compile javafx:run` — build & launch GUI.
- `mvn test` — run **69 tests** (JUnit 5 + Mockito, no Spring context, fast).
- Single test class: `mvn test -Dtest=TreemapLayoutTest`.
- Tests use `@TempDir` for filesystem tests, no external services needed.

## Platform support

| OS | Key path | Elevated scan |
|----|----------|---------------|
| Linux | `$HOME`, `$XDG_CACHE_HOME`, `/var/` | `pkexec` + bash helper script |
| macOS | `$HOME/Library/`, `/Library/` | `osascript` with admin privileges |
| Windows | `%WINDIR%`, `%TEMP%`, `%LOCALAPPDATA%` | PowerShell `Start-Process -Verb RunAs` (UAC) |

All paths use `java.nio.file.Path` — no platform-specific code in scanner/model layers.

## Architecture

```
by.snql.filescanner/
├── Main.java              # JavaFX Application entrypoint (GUI only — no CLI mode)
├── model/FileNode.java    # Tree node: path, name, size, children, symlink, hardlink, buildArtifact, lastModified
├── scanner/FileScanner.java  # Recursive FS walk, Virtual Threads. Each scan() call runs in its own
│                            #   isolated Session (no shared mutable state across concurrent/sequential scans).
│                            #   Depends only on model + core.project — NOT on config/ui.
├── config/
│   ├── Settings.java      # Persistent settings: dark mode, hidden files, SHA-256, scan roots, moveToTrash, recentPaths
│   └── CacheManager.java  # Persists the last completed scan (versioned schema, atomic writes) for instant startup
├── core/                  # Platform/business logic — NO JavaFX imports, independently testable
│   ├── project/ProjectType.java   # Enum: project detection by config file (pom.xml, package.json, Cargo.toml...)
│   ├── analysis/
│   │   ├── FileAnalysis.java       # Pure utility: flatten, largest, types, SHA-256 dupes, old files, empty dirs
│   │   ├── FileGrouper.java        # Alternative grouping: by file type, age bucket, or file owner
│   │   ├── FileCategory.java       # File extension -> category (image/video/code/...), no JavaFX Color
│   │   └── CompressionEstimate.java # Heuristic compression ratio estimation per file category
│   ├── cleanup/
│   │   ├── SystemCleanup.java      # OS-specific cleanup targets from cleanup-targets.json, elevation, CleanupPlan
│   │   └── DeletionService.java    # Centralized deletion: trash by default, reports per-path success/failure
│   ├── export/
│   │   ├── ExportUtils.java        # Streaming CSV/JSON/HTML export (no whole-tree String in memory)
│   │   ├── PdfReport.java          # PDF report, invoked from the GUI's Export menu
│   │   └── SnapshotManager.java    # Save/load/compare disk usage snapshots with diff view
│   └── util/SizeFormat.java        # Locale-independent "1.5 MB" formatting (single source of truth)
└── ui/
    ├── MainWindow.java       # Orchestrator: toolbar, tree, charts, drag&drop, history, glob/regex filter, breadcrumb nav
    ├── TreemapChart.java     # Canvas treemap (squarified layout), file-type coloring
    ├── RingsChart.java       # Canvas sunburst rings — proper annulus rendering (compass angle internally,
    │                          #   converted to JavaFX's arc convention only at draw time; matches hit-testing)
    ├── TreemapLayout.java    # Layout algorithm (isolated from JavaFX, testable, depth-limited for safety)
    ├── FileTypeCategory.java # Maps core.analysis.FileCategory to JavaFX colors
    └── BottomTabs.java       # TabPane: Largest, Types, Duplicates, Empty Dirs, Old Files, Cleanup, Project
                                #   Cleanup, Compress, Groups, Snapshots — lazily refreshed (only the visible
                                #   tab recomputes on scan; a generation counter discards stale background results)
```

- Scanner uses **single-pass algorithm** — no preliminary `countAll` walk. Progress is real-time. Each `scan()`
  call creates a private `Session` object, so concurrent or back-to-back scans on the same `FileScanner`
  instance never share mutable state (this used to be a real bug: two scans on one instance would corrupt
  each other's result tree).
- Symlinks are **never followed** (checked via `Files.isSymbolicLink` before `Files.isDirectory`, which
  would otherwise silently follow a symlinked directory); directory cycles (junctions/bind mounts) are
  detected via `fileKey()` and stopped rather than walked forever.
- Duplicate finder uses **streaming SHA-256** (`DigestInputStream`, 8KB buffer) — no `readAllBytes`, safe for
  large files. Unreadable files are excluded rather than given a fake "hash" that never collides.
- **Deletion always goes through `DeletionService`**: moves to the OS trash/recycle bin by default
  (`Settings.moveToTrash`, on by default); every deletion reports exactly which paths succeeded/failed
  instead of swallowing `IOException`.
- **Project Cleanup** tab detects project types via config files (`pom.xml` → Maven, `package.json` → Node.js,
  `Cargo.toml` → Rust, etc.), finds build artifacts (`target/`, `node_modules/`, `build/`, `__pycache__/`,
  `.next/`, etc.) in scan roots, with per-artifact and bulk deletion. Directory listings are read once per
  directory and reused for both project-type detection and recursion (previously listed twice, plus up to
  ~15 `Files.exists` stats per directory for type detection).
- Cleanup tab scans targets **in parallel via Virtual Threads** (`CompletableFuture.allOf`); sizes for build
  artifacts are computed on the background thread, never inside `Platform.runLater`.
- Cleanup targets support `contentsOnly` (clear a folder's contents, keep the folder), `daysOld`, `extension`,
  and `filesOnly` (glob-matched files, e.g. `thumbcache_*.db` — resolved without ever calling `Path.of` on a
  string containing a wildcard, which is invalid on Windows) filters, plus a `risk: "high"` flag that adds an
  extra confirmation. Destructive defaults (installer caches, prefetch, recycle bin internals, whole `/tmp`
  or `/var/log` trees) have been removed or scoped down in `cleanup-targets.json`.
- `customCommand` targets always show the exact command in a confirmation dialog before running it.
- Chart views are independent Canvas nodes inside a StackPane, toggled via ToggleButtons. Resize
  notifications (width+height change together) are coalesced into a single redraw via `Platform.runLater`
  instead of computing the layout twice per resize.
- Cleanup tab loads targets from `src/main/resources/cleanup-targets.json`, merged with user config
  `~/.filescanner/cleanup-targets.json`. A malformed entry in either file is skipped with a logged warning,
  not allowed to abort loading of the rest.
- Deletion: right-click context menu or `Delete` key, multi-select via `SelectionMode.MULTIPLE`, confirmation
  dialog with size; duplicate-file checkboxes carry the `FileNode` as `userData` (not parsed back out of the
  displayed label text).

## Test coverage

| Class | Tests | What it covers |
|-------|-------|----------------|
| `model.FileNodeTest` | 15 | constructor, addChild/attachChild (no double-counting), copyOf, sortChildren, isLeaf, setSize |
| `scanner.FileScannerTest` | 11 | all scan scenarios, cancel, progress, symlinks (not followed), concurrent scans isolated |
| `core.util.SizeFormatTest` | 15 | B/KB/MB/GB/TB boundaries, zero/negative, locale independence, parameterized |
| `ui.TreemapLayoutTest` | 19 | null/zero sizes, layout, non-overlap, squarify, displayDepth, countDescendants |
| `core.cleanup.SystemCleanupTest` | 9 | resolvePath, calculateSize, targets per-OS; elevation API is only tested with an empty list — never triggers a real UAC/pkexec/osascript prompt from the test suite |

**69 total**, all pass (one symlink test is skipped rather than failed on systems/users without
permission to create symlinks). Test classes are pure logic - no JavaFX GUI tests (JavaFX requires
headless init, not worth the complexity for Canvas-based rendering).

## Key conventions

- Module system: `module-info.java` requires `javafx.controls`, `javafx.fxml`, `java.desktop`, `java.logging`,
  `com.google.gson`. `opens by.snql.filescanner.config` and `opens by.snql.filescanner.core.cleanup` to
  `com.google.gson` for reflective JSON parsing (Settings, SystemCleanup's target records).
- Gson for JSON (cleanup-targets.json, user config, elevation output, settings).
- No Lombok, no Spring, no DI framework — plain Java with `new`.
- CSS: `src/main/resources/styles/main.css` (base) + `dark.css` (dark-mode overrides, applied/removed
  from the scene's stylesheet list on toggle — no inline `-fx-style` strings).
- Run scripts: `run.sh` (Linux/macOS, LF line endings — a CRLF shebang line breaks it on Linux),
  `run.bat` (Windows) — both do `mvn -q compile javafx:run`.
- Settings: `~/.filescanner/settings.json` — `duplicateSHA256` (off by default), `scanHidden`, `darkMode`,
  `defaultSort`, `moveToTrash` (on by default), `recentPaths` (scan history, persisted across restarts).
  Toggle via ⚙ button in toolbar.

## Documentation maintenance

**CRITICAL: After every feature change, update the following files:**
1. `README.md` — feature list, architecture, usage examples
2. `INSTRUCTIONS.md` — detailed usage guide, config/cleanup-target reference
3. `AGENTS.md` — this file, architecture overview

If you added new files, update the architecture tree. If you added a settings field, update the
settings example. If you added tabs, update the tabs table.

**Language: all documentation MUST be written in English.** Code comments, commit messages, README, INSTRUCTIONS — everything in English.
