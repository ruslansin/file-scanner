# AGENTS.md

Disk space analyzer with treemap & rings visualization in JavaFX. Cross-platform GNOME Baobab clone.

## Build & test

- Java **21** required. No Maven wrapper — use system `mvn`.
- `mvn compile javafx:run` — build & launch GUI.
- `mvn test` — run **65 tests** (JUnit 5 + Mockito, no Spring context, fast).
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
├── Main.java              # JavaFX Application entrypoint
├── model/FileNode.java    # Tree node: path, name, size, children, symlink, hardlink, buildArtifact flags
├── scanner/FileScanner.java  # Recursive FS walk + ScanProgress, Virtual Threads, volatile cancel, link detection
└── ui/
    ├── MainWindow.java       # Orchestrator: toolbar, tree, charts, drag&drop, history, glob/regex filter
    ├── TreemapChart.java     # Canvas treemap (squarified layout), file-type coloring
    ├── RingsChart.java       # Canvas sunburst rings, file-type coloring
    ├── TreemapLayout.java    # Layout algorithm (isolated from JavaFX, testable)
    ├── FileTypeCategory.java # File type enum: IMAGE/VIDEO/AUDIO/DOCUMENT/ARCHIVE/CODE/etc with colors
    ├── ProjectType.java      # Enum: project detection by config file (pom.xml, package.json, Cargo.toml...)
    ├── BottomTabs.java       # TabPane: Largest, Types, Duplicates, Empty Dirs, Old Files, Cleanup, Project Cleanup, Compress, Groups, Snapshots
    ├── FileAnalysis.java     # Pure utility: flatten, largest, types, SHA-256 dupes, old files, empty dirs
    ├── FileGrouper.java      # Alternative grouping: by file type, age bucket, or file owner
    ├── CompressionEstimate.java  # Heuristic compression ratio estimation per file category
    ├── SnapshotManager.java  # Save/load/compare disk usage snapshots with diff view
    ├── FileScannerCli.java   # CLI mode: --scan, --largest, --duplicates, --types, --empty, --json
    ├── ExportUtils.java      # CSV/JSON/HTML export
    ├── SystemCleanup.java    # OS-specific cache targets from cleanup-targets.json, elevation
    └── Settings.java         # Persistent settings: dark mode, hidden files, SHA-256, scan roots, project scan toggle
```

- Scanner uses **single-pass algorithm** with `AtomicLong` discovered/processed counters — no preliminary `countAll` walk. Progress is real-time.
- Duplicate finder uses **streaming SHA-256** (`DigestInputStream`, 8KB buffer) — no `readAllBytes`, safe for large files.
- **Project Cleanup** tab detects project types via config files (`pom.xml` → Maven, `package.json` → Node.js, `Cargo.toml` → Rust, etc.), finds build artifacts (`target/`, `node_modules/`, `build/`, `__pycache__/`, `.next/`, etc.) in scan roots, with per-artifact and bulk deletion.
- Cleanup tab scans targets **in parallel via Virtual Threads** (`CompletableFuture.allOf`), non-blocking UI.
- Chart views are independent Canvas nodes inside a StackPane, toggled via ToggleButtons.
- Cleanup tab loads targets from `src/main/resources/cleanup-targets.json`, merged with user config `~/.filescanner/cleanup-targets.json`.
- Deletion: right-click context menu or `Delete` key, multi-select via `SelectionMode.MULTIPLE`, confirmation dialog with size.

## Test coverage

| Class | Tests | What it covers |
|-------|-------|----------------|
| `FileNodeTest` | 11 | constructor, addChild, sortChildren, isLeaf, setSize |
| `FileScannerTest` | 11 | all scan scenarios, cancel, progress, symlinks, concurrent scans |
| `FormatSizeTest` | 14 | B/KB/MB/GB/TB boundaries, zero/negative, parameterized |
| `TreemapLayoutTest` | 19 | null/zero sizes, layout, non-overlap, squarify, displayDepth, countDescendants |
| `SystemCleanupTest` | 10 | resolvePath, calculateSize, targets per-OS, elevation API |

**65 total**, all pass. Test classes are pure logic — no JavaFX GUI tests (JavaFX requires headless init, not worth the complexity for Canvas-based rendering).

## Key conventions

- Module system: `module-info.java` requires `javafx.controls`, `javafx.fxml`, `java.desktop`, `com.google.gson`. `opens by.snql.filescanner.ui to com.google.gson` for reflective JSON parsing.
- Gson for JSON (cleanup-targets.json, user config, elevation output, settings).
- No Lombok, no Spring, no DI framework — plain Java with `new`.
- CSS: `src/main/resources/styles/main.css`, loaded via `getClass().getResource()`.
- Run scripts: `run.sh` (Linux/macOS), `run.bat` (Windows) — both do `mvn -q compile javafx:run`.
- Settings: `~/.filescanner/settings.json` — `duplicateSHA256` (off by default), `scanHidden`, `darkMode`, `defaultSort`. Toggle via ⚙ button in toolbar.

## Documentation maintenance

**CRITICAL: After every feature change, update the following files:**
1. `README.md` — feature list, architecture, usage examples
2. `INSTRUCTIONS.md` — detailed usage guide, config reference, CLI flags
3. `AGENTS.md` — this file, architecture overview

If you added new files, update the architecture tree. If you added CLI flags, update the CLI section. If you added tabs, update the tabs table.

**Language: all documentation MUST be written in English.** Code comments, commit messages, README, INSTRUCTIONS — everything in English.
