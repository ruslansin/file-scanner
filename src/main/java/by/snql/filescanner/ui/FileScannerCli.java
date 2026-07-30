package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import by.snql.filescanner.scanner.FileScanner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class FileScannerCli {

    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB", "TB"};
    private static final String DIVIDER = "─".repeat(60);

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String dir = null;
        boolean json = false;
        boolean pdf = false;
        String pdfPath = null;
        boolean largest = false;
        boolean duplicates = false;
        boolean types = false;
        boolean empty = false;
        boolean compress = false;
        boolean groups = false;
        int limit = 50;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--scan", "-s" -> dir = args[++i];
                case "--json", "-j" -> json = true;
                case "--pdf" -> { pdf = true; pdfPath = args[++i]; }
                case "--largest", "-l" -> largest = true;
                case "--duplicates", "-d" -> duplicates = true;
                case "--types", "-t" -> types = true;
                case "--empty", "-e" -> empty = true;
                case "--compress", "-c" -> compress = true;
                case "--groups", "-g" -> groups = true;
                case "--all", "-a" -> largest = duplicates = types = empty = compress = groups = true;
                case "--limit", "-n" -> limit = Integer.parseInt(args[++i]);
                case "--help", "-h" -> { printUsage(); return; }
                default -> {}
            }
        }

        if (dir == null) {
            System.err.println("Error: --scan <dir> is required");
            printUsage();
            return;
        }

        var path = Path.of(dir);
        if (!Files.isDirectory(path)) {
            System.err.println("Error: " + dir + " is not a directory");
            return;
        }

        if (!largest && !duplicates && !types && !empty && !compress && !groups) {
            largest = true;
        }

        var scanner = new FileScanner();
        try {
            if (!json) {
                System.err.print("Scanning " + path + "...");
                System.err.flush();
            }

            var node = scanner.scan(path, p -> {}).get();
            if (node == null) {
                System.err.println(" cancelled or failed");
                return;
            }
            node.sortChildren();

            if (!json) System.err.println(" done.");

            var allFiles = FileAnalysis.flattenFiles(node);
            long dirCount = countDirs(node);
            long linkCount = allFiles.stream().filter(FileNode::isSymlink).count();

            if (pdf && pdfPath != null) {
                if (!json) System.err.print("Generating PDF report...");
                PdfReport.generate(node, Path.of(pdfPath), limit);
                if (!json) System.err.println(" " + pdfPath);
            }

            if (!json) printSummary(path, node, allFiles.size(), dirCount, linkCount);

            var result = new LinkedHashMap<String, Object>();
            result.put("root", path.toString());
            result.put("total_size", node.getSize());
            result.put("total_size_human", formatSize(node.getSize()));
            result.put("file_count", allFiles.size());
            result.put("dir_count", dirCount);
            result.put("symlink_count", linkCount);

            if (largest) outputLargest(node, limit, json, result);
            outputTree(node, json, result);
            if (duplicates) outputDuplicates(node, json, result);
            if (empty) outputEmpty(node, json, result);
            if (compress) outputCompress(node, json, result);
            if (groups) outputGroups(node, json, result);

            if (json) {
                var gson = new GsonBuilder().setPrettyPrinting().create();
                System.out.println(gson.toJson(result));
            }
        } catch (Exception e) {
            System.err.println("Scan error: " + e.getMessage());
        }
    }

    private static long countDirs(FileNode node) {
        long count = node.isDirectory() ? 1 : 0;
        for (var child : node.getChildren()) count += countDirs(child);
        return count;
    }

    private static void printSummary(Path root, FileNode node, long fileCount, long dirCount, long linkCount) {
        System.out.println();
        System.out.println(DIVIDER);
        System.out.println("  SCAN SUMMARY");
        System.out.println(DIVIDER);
        System.out.printf("  Root:          %s%n", root);
        System.out.printf("  Total size:    %s%n", formatSize(node.getSize()));
        System.out.printf("  Files:         %,d%n", fileCount);
        System.out.printf("  Directories:   %,d%n", dirCount);
        if (linkCount > 0) System.out.printf("  Symlinks:      %,d%n", linkCount);
        System.out.printf("  Disk free:     %s%n", diskFree(root));
        System.out.println(DIVIDER);
    }

    private static String diskFree(Path path) {
        try {
            var store = Files.getFileStore(path);
            return formatSize(store.getUsableSpace()) + " free of " + formatSize(store.getTotalSpace());
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static void outputLargest(FileNode node, int limit, boolean json, Map<String, Object> result) {
        var files = FileAnalysis.largestFiles(node, limit);
        if (!json) {
            System.out.println();
            System.out.println(DIVIDER);
            System.out.printf("  LARGEST %d FILES%n", files.size());
            System.out.println(DIVIDER);
            int i = 1;
            for (var f : files) {
                System.out.printf("  %3d. %-10s  %s%n", i++, formatSize(f.getSize()), f.getPath());
            }
        }
        result.put("largest_files", files.stream()
                .map(f -> Map.of("path", f.getPath().toString(), "size", f.getSize(), "size_human", formatSize(f.getSize())))
                .collect(Collectors.toList()));
    }

    private static void outputTree(FileNode node, boolean json, Map<String, Object> result) {
        var entries = FileAnalysis.directoryTree(node, 4, 8);
        if (!json) {
            System.out.println();
            System.out.println(DIVIDER);
            System.out.println("  DIRECTORY TREE (4 levels, 8 children per level)");
            System.out.println(DIVIDER);
            var open = new boolean[16];
            for (var e : entries) {
                var prefix = new StringBuilder();
                for (int d = 1; d <= e.depth(); d++) {
                    prefix.append(d == e.depth()
                            ? (e.last() ? "└── " : "├── ")
                            : (open[d] ? "│   " : "    "));
                }
                open[e.depth()] = !e.last();
                System.out.printf("  %10s  %s%s%n",
                        formatSize(e.size()), prefix, e.name());
            }
        }
    }

    private static void outputTypes(FileNode node, boolean json, Map<String, Object> result) {
        var map = FileAnalysis.fileTypeBreakdown(node);
        var sorted = map.values().stream()
                .sorted(Comparator.comparingLong(FileAnalysis.FileTypeStat::getTotalSize).reversed())
                .toList();
        if (!json) {
            System.out.println();
            System.out.println(DIVIDER);
            System.out.println("  FILE TYPES");
            System.out.println(DIVIDER);
            System.out.printf("  %-15s  %6s  %10s  %s%n", "Extension", "Files", "Size", "Share");
            System.out.println("  " + "-".repeat(50));
            long total = sorted.stream().mapToLong(FileAnalysis.FileTypeStat::getTotalSize).sum();
            for (var s : sorted) {
                double pct = total > 0 ? (double) s.getTotalSize() / total * 100 : 0;
                System.out.printf("  %-15s  %,6d  %10s  %5.1f%%%n",
                        s.getExtension(), s.getCount(), formatSize(s.getTotalSize()), pct);
            }
        }
        result.put("file_types", sorted.stream()
                .map(t -> Map.of("extension", t.getExtension(), "count", t.getCount(),
                        "total_size", t.getTotalSize(), "total_size_human", formatSize(t.getTotalSize())))
                .collect(Collectors.toList()));
    }

    private static void outputDuplicates(FileNode node, boolean json, Map<String, Object> result) {
        if (!Settings.get().duplicateSHA256) {
            if (!json) {
                System.out.println();
                System.out.println(DIVIDER);
                System.out.println("  DUPLICATES (disabled — enable SHA-256 in Settings)");
                System.out.println(DIVIDER);
            }
            result.put("duplicates", List.of());
            return;
        }
        var groups = FileAnalysis.findDuplicates(node);
        if (!json) {
            System.out.println();
            System.out.println(DIVIDER);
            System.out.println("  DUPLICATES");
            System.out.println(DIVIDER);
            if (groups.isEmpty()) {
                System.out.println("  None found.");
            } else {
                long totalWaste = FileAnalysis.totalDuplicateWaste(groups);
                System.out.printf("  Showing top %d groups, total waste: %s%n", groups.size(), formatSize(totalWaste));
                System.out.println("  Excludes: .git/, node_modules/, target/, __pycache__/, build/, dist/, vendor/, .venv/");
                System.out.println();
                for (var g : groups) {
                    String prefix = FileAnalysis.commonPathPrefix(g.files);
                    System.out.printf("  %d files × %s each  (waste: %s)%n",
                            g.files.size(), formatSize(g.fileSize), formatSize(g.wastedSize()));
                    if (!prefix.isEmpty()) System.out.println("    in " + prefix);
                    int shown = 0;
                    for (var f : g.files) {
                        if (shown++ >= 3) break;
                        System.out.println("    " + f.getPath());
                    }
                    if (g.files.size() > 3) System.out.println("    ... and " + (g.files.size() - 3) + " more files");
                    System.out.println();
                }
            }
        }
        result.put("duplicates", groups.stream()
                .map(g -> Map.of("count", g.files.size(), "file_size", g.fileSize,
                        "file_size_human", formatSize(g.fileSize), "wasted", g.wastedSize(),
                        "wasted_human", formatSize(g.wastedSize()),
                        "files", g.files.stream().map(f -> f.getPath().toString()).collect(Collectors.toList())))
                .collect(Collectors.toList()));
    }

    private static void outputEmpty(FileNode node, boolean json, Map<String, Object> result) {
        var dirs = FileAnalysis.findEmptyDirs(node);
        if (!json) {
            System.out.println();
            System.out.println(DIVIDER);
            System.out.println("  EMPTY DIRECTORIES");
            System.out.println(DIVIDER);
            if (dirs.isEmpty()) {
                System.out.println("  None found.");
            } else {
                System.out.printf("  %d empty directories:%n", dirs.size());
                for (var d : dirs) System.out.println("    " + d.getPath());
            }
        }
        result.put("empty_dirs", dirs.stream().map(d -> d.getPath().toString()).collect(Collectors.toList()));
    }

    private static void outputCompress(FileNode node, boolean json, Map<String, Object> result) {
        var estimate = CompressionEstimate.estimate(node);
        if (!json) {
            System.out.println();
            System.out.println(DIVIDER);
            System.out.println("  COMPRESSION ESTIMATE");
            System.out.println(DIVIDER);
            System.out.printf("  Original:            %s%n", formatSize(estimate.originalSize()));
            System.out.printf("  Est. compressed:     %s%n", formatSize(estimate.estimatedCompressed()));
            System.out.printf("  Could save:          %s (%.0f%%)%n",
                    formatSize(estimate.savings()), (1 - estimate.ratio()) * 100);
            System.out.printf("  Strategy:            %s%n", estimate.strategy());
            System.out.println();

            var byCat = new LinkedHashMap<String, long[]>();
            var files = FileAnalysis.flattenFiles(node);
            for (var f : files) {
                String cat = categoryName(f.getName());
                var arr = byCat.computeIfAbsent(cat, k -> new long[2]);
                arr[0] += f.getSize();
                arr[1] += CompressionEstimate.estimateCompressedSize(f);
            }

            System.out.printf("  %-15s  %10s  %10s  %10s  %s%n", "Category", "Original", "Compressed", "Savings", "Ratio");
            System.out.println("  " + "-".repeat(65));
            for (var entry : byCat.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                    .toList()) {
                long orig = entry.getValue()[0];
                long comp = entry.getValue()[1];
                long save = orig - comp;
                double ratio = orig > 0 ? (double) comp / orig * 100 : 100;
                System.out.printf("  %-15s  %10s  %10s  %10s  %4.0f%%%n",
                        entry.getKey(), formatSize(orig), formatSize(comp), formatSize(save), ratio);
            }
        }
        result.put("compression", Map.of(
                "original", estimate.originalSize(),
                "estimated_compressed", estimate.estimatedCompressed(),
                "savings", estimate.savings(),
                "ratio", estimate.ratio()));
    }

    private static void outputGroups(FileNode node, boolean json, Map<String, Object> result) {
        if (!json) {
            for (var mode : new String[]{"File Type", "Age", "Owner"}) {
                var groups = switch (mode) {
                    case "Age" -> FileGrouper.byAge(node);
                    case "Owner" -> FileGrouper.byOwner(node);
                    default -> FileGrouper.byFileType(node);
                };
                if (groups.isEmpty()) continue;

                System.out.println();
                System.out.println(DIVIDER);
                System.out.printf("  GROUPED BY %s%n", mode.toUpperCase());
                System.out.println(DIVIDER);

                long total = groups.stream().mapToLong(FileGrouper.Group::totalSize).sum();
                System.out.printf("  %-20s  %6s  %10s  %6s%n", "Group", "Files", "Size", "Share");
                System.out.println("  " + "-".repeat(50));
                for (var g : groups) {
                    double pct = total > 0 ? (double) g.totalSize() / total * 100 : 0;
                    System.out.printf("  %-20s  %,6d  %10s  %5.1f%%%n",
                            truncate(g.name(), 20), g.fileCount(), formatSize(g.totalSize()), pct);
                }
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String categoryName(String name) {
        String ext = ext(name);
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "bmp", "svg", "webp", "ico", "tiff", "psd", "raw", "heic" -> "Image";
            case "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp" -> "Video";
            case "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus" -> "Audio";
            case "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods" -> "Document";
            case "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "jar", "war", "iso", "dmg" -> "Archive";
            case "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php",
                 "swift", "kt", "scala", "lua", "sh", "bash", "sql", "html", "css", "xml", "json" -> "Code";
            case "exe", "dll", "so", "dylib", "bin", "app", "msi" -> "Executable";
            case "ttf", "otf", "woff", "woff2" -> "Font";
            default -> "Other";
        };
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static void printUsage() {
        System.out.println("""
            File Scanner CLI — Disk Space Analyzer
            
            Usage: java -cp file-scanner.jar by.snql.filescanner.ui.FileScannerCli [options]
            
            Scan options:
              --scan, -s <dir>    Directory to scan (required)
            
            Report sections:
              --all, -a           All sections (default: largest + types)
              --largest, -l       Largest files
              --types, -t         File type breakdown
              --duplicates, -d    Duplicate files
              --empty, -e         Empty directories
              --compress, -c      Compression savings estimate
              --groups, -g        Files grouped by type, age, owner
            
            Output:
              --json, -j          JSON output instead of text
              --pdf <file>        Generate PDF report
              --sha256            Use SHA-256 for exact duplicate matching (slower)
              --limit, -n <N>     Max files in largest list (default 50)
              --help, -h          Show this help
            
            Examples:
              java -cp file-scanner.jar FileScannerCli --scan ~
              java -cp file-scanner.jar FileScannerCli -s ~ -a -j > report.json
              java -cp file-scanner.jar FileScannerCli -s /home -l -c -n 20
              java -cp file-scanner.jar FileScannerCli -s ~ --pdf report.pdf
            """);
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        int unit = (int) (Math.log10(bytes) / Math.log10(1024));
        unit = Math.min(unit, SIZE_UNITS.length - 1);
        double value = bytes / Math.pow(1024, unit);
        return String.format("%.1f %s", value, SIZE_UNITS[unit]);
    }
}
