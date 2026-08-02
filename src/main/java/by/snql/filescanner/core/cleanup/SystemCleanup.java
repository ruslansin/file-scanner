package by.snql.filescanner.core.cleanup;

import by.snql.filescanner.config.Settings;
import by.snql.filescanner.core.project.ProjectType;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SystemCleanup {

    private static final Logger LOG = Logger.getLogger(SystemCleanup.class.getName());

    private SystemCleanup() {}

    /**
     * A single cleanable location. {@code filesOnly} means {@code path}'s last segment
     * is a glob pattern matching files directly inside its parent (e.g. thumbnail caches);
     * {@code contentsOnly} means only the directory's contents should be removed, not the
     * directory entry itself (needed for locations like %TEMP% that must keep existing);
     * {@code daysOld}/{@code extension} further restrict which files within a target count
     * towards size / deletion. {@code risk} is surfaced in the UI ("high" prompts an extra
     * confirmation) since not all cleanup targets carry the same blast radius.
     */
    public record Target(String name, String path, String description, String customCommand,
                          boolean contentsOnly, Integer daysOld, String extension, boolean filesOnly,
                          String risk) {
        public Target(String name, String path, String description) {
            this(name, path, description, null, false, null, null, false, "low");
        }

        public boolean isHighRisk() { return "high".equalsIgnoreCase(risk); }
    }

    public record BuildArtifact(ProjectType projectType, String artifactName, Path path, Path projectDir) {}

    /** What deleting/sizing a target actually touches on disk. */
    public sealed interface CleanupPlan {
        record Empty() implements CleanupPlan {}
        record Files(List<Path> files) implements CleanupPlan {}
        record WholePath(Path path) implements CleanupPlan {}
        record DirectoryContents(Path dir) implements CleanupPlan {}
    }

    private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    private static final String USER_HOME = System.getProperty("user.home");
    private static final Gson GSON = new Gson();

    private static List<Target> cached;

    public static List<Target> targets() {
        if (cached != null) return cached;
        cached = loadTargets();
        return cached;
    }

    public static List<Path> scanRoots() {
        var roots = new ArrayList<Path>();
        try (var in = SystemCleanup.class.getResourceAsStream("/cleanup-targets.json")) {
            if (in != null) {
                var root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("scanRoots")) {
                    for (var el : root.getAsJsonArray("scanRoots")) {
                        var resolved = safeResolveDir(el.getAsString());
                        if (resolved != null && !roots.contains(resolved)) roots.add(resolved);
                    }
                }
            }
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            LOG.log(Level.WARNING, "Failed to read bundled scan roots", e);
        }

        for (var r : Settings.get().scanRoots) {
            var resolved = safeResolveDir(r);
            if (resolved != null && !roots.contains(resolved)) roots.add(resolved);
        }
        return roots;
    }

    private static Path safeResolveDir(String raw) {
        try {
            var resolved = Path.of(substituteVars(raw));
            return Files.exists(resolved) ? resolved : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    public static List<BuildArtifact> findBuildArtifacts(List<Path> roots) {
        if (!Settings.get().projectScanEnabled) return List.of();
        var result = new ArrayList<BuildArtifact>();
        for (var root : roots) {
            findInDir(root, 0, Settings.get().projectScanDepth, result);
        }
        return result;
    }

    private static void findInDir(Path dir, int depth, int maxDepth, List<BuildArtifact> result) {
        if (depth > maxDepth || !Files.isDirectory(dir)) return;

        // List the directory once and reuse it both for project-type detection
        // (which used to stat() up to ~15 candidate marker files individually)
        // and for recursing into subdirectories.
        List<Path> entries;
        try (var stream = Files.list(dir)) {
            entries = stream.toList();
        } catch (IOException ignored) {
            return; // Permission denied on this subtree — skip it.
        }

        var names = entries.stream().map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        var type = ProjectType.detect(names);
        if (type.isPresent()) {
            var t = type.get();
            for (var artifact : t.artifacts()) {
                var artifactPath = dir.resolve(artifact);
                if (Files.isDirectory(artifactPath)) {
                    result.add(new BuildArtifact(t, artifact, artifactPath, dir));
                }
            }
        }

        for (var entry : entries) {
            if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                var name = entry.getFileName().toString();
                if (!name.startsWith(".") || depth < 3) {
                    findInDir(entry, depth + 1, maxDepth, result);
                }
            }
        }
    }

    private static String substituteVars(String raw) {
        if (OS.contains("win")) raw = raw.replace("/", "\\");
        raw = raw.replace("$HOME", USER_HOME);
        raw = raw.replace("%WINDIR%", envOr("WINDIR", "C:\\Windows"));
        raw = raw.replace("%TEMP%", envOr("TEMP", System.getProperty("java.io.tmpdir")));
        raw = raw.replace("%LOCALAPPDATA%", envOr("LOCALAPPDATA", USER_HOME + "\\AppData\\Local"));
        raw = raw.replace("%APPDATA%", envOr("APPDATA", USER_HOME + "\\AppData\\Roaming"));
        raw = raw.replace("$XDG_CACHE_HOME", envOr("XDG_CACHE_HOME", USER_HOME + "/.cache"));
        return raw;
    }

    /**
     * Resolves a target's base location. For {@code filesOnly} targets this returns
     * the *parent directory* of the glob pattern (never calls {@code Path.of} on a
     * string containing wildcard characters, which is invalid on Windows). Returns
     * null if nothing exists on disk.
     */
    public static Path resolvePath(Target target) {
        if (target.path() == null) return null;
        String substituted = substituteVars(target.path());
        try {
            if (target.filesOnly()) {
                return parentOf(substituted);
            }
            var path = Path.of(substituted);
            return Files.exists(path) ? path : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static Path parentOf(String substitutedPathWithGlob) {
        int lastSep = Math.max(substitutedPathWithGlob.lastIndexOf('\\'), substitutedPathWithGlob.lastIndexOf('/'));
        if (lastSep < 0) return null;
        try {
            var parent = Path.of(substitutedPathWithGlob.substring(0, lastSep));
            return Files.isDirectory(parent) ? parent : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String globPattern(String substitutedPathWithGlob) {
        int lastSep = Math.max(substitutedPathWithGlob.lastIndexOf('\\'), substitutedPathWithGlob.lastIndexOf('/'));
        return lastSep < 0 ? substitutedPathWithGlob : substitutedPathWithGlob.substring(lastSep + 1);
    }

    public static CleanupPlan planFor(Target target) {
        if (target.filesOnly()) {
            var parent = resolvePath(target);
            if (parent == null) return new CleanupPlan.Empty();
            var pattern = globPattern(substituteVars(target.path()));
            var matches = new ArrayList<Path>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, pattern)) {
                for (var p : stream) if (Files.isRegularFile(p)) matches.add(p);
            } catch (IOException ignored) {
                return new CleanupPlan.Empty();
            }
            return matches.isEmpty() ? new CleanupPlan.Empty() : new CleanupPlan.Files(matches);
        }

        var resolved = resolvePath(target);
        if (resolved == null) return new CleanupPlan.Empty();

        if (target.daysOld() != null || target.extension() != null) {
            var matches = filteredFiles(resolved, target);
            return matches.isEmpty() ? new CleanupPlan.Empty() : new CleanupPlan.Files(matches);
        }

        if (Files.isDirectory(resolved) && target.contentsOnly()) {
            return new CleanupPlan.DirectoryContents(resolved);
        }
        return new CleanupPlan.WholePath(resolved);
    }

    private static List<Path> filteredFiles(Path root, Target target) {
        long cutoffMillis = target.daysOld() != null
                ? System.currentTimeMillis() - target.daysOld() * 24L * 3600 * 1000
                : Long.MAX_VALUE;
        String suffix = target.extension();

        if (!Files.isDirectory(root)) {
            return matchesFilter(root, cutoffMillis, suffix) ? List.of(root) : List.of();
        }
        var result = new ArrayList<Path>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> matchesFilter(p, cutoffMillis, suffix))
                    .forEach(result::add);
        } catch (IOException ignored) {
            // Partial results are acceptable — a permission error on a subtree just
            // means those files are excluded from this cleanup pass.
        }
        return result;
    }

    private static boolean matchesFilter(Path file, long cutoffMillis, String suffix) {
        if (suffix != null && !file.getFileName().toString().toLowerCase(Locale.ROOT)
                .endsWith(suffix.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (cutoffMillis != Long.MAX_VALUE) {
            try {
                if (Files.getLastModifiedTime(file).toMillis() >= cutoffMillis) return false;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    public static long calculateSize(Target target) {
        return planSize(planFor(target));
    }

    private static long planSize(CleanupPlan plan) {
        return switch (plan) {
            case CleanupPlan.Empty e -> 0;
            case CleanupPlan.Files f -> f.files().stream().mapToLong(SystemCleanup::safeSize).sum();
            case CleanupPlan.WholePath w -> walkSizeSafe(w.path());
            case CleanupPlan.DirectoryContents d -> walkSizeSafe(d.dir());
        };
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Deletes everything covered by this target's plan. Honors trash-vs-permanent via Settings. */
    public static DeletionService.DeletionResult delete(Target target) {
        boolean moveToTrash = Settings.get().moveToTrash;
        return switch (planFor(target)) {
            case CleanupPlan.Empty e -> new DeletionService.DeletionResult(List.of(), Map.of());
            case CleanupPlan.Files f -> DeletionService.delete(f.files(), moveToTrash);
            case CleanupPlan.WholePath w -> DeletionService.delete(List.of(w.path()), moveToTrash);
            case CleanupPlan.DirectoryContents d -> DeletionService.deleteContents(d.dir(), moveToTrash);
        };
    }

    public static long walkSizeSafe(Path path) {
        if (path == null || !Files.exists(path)) return 0;
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    return stream.filter(Files::isRegularFile).mapToLong(SystemCleanup::safeSize).sum();
                }
            }
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    public static Map<Path, Long> calculateSizesViaElevation(List<Path> paths) {
        if (paths.isEmpty()) return Map.of();
        var result = new LinkedHashMap<Path, Long>();

        if (OS.contains("win")) {
            calculateSizesViaElevationWindows(paths, result);
        } else if (OS.contains("linux")) {
            calculateSizesViaElevationLinux(paths, result);
        } else if (OS.contains("mac")) {
            calculateSizesViaElevationMac(paths, result);
        }
        return result;
    }

    private static void calculateSizesViaElevationWindows(List<Path> paths, Map<Path, Long> result) {
        try {
            var runDir = Path.of(USER_HOME, ".filescanner", "run");
            Files.createDirectories(runDir);
            String token = Long.toHexString(System.nanoTime());
            var inputFile = runDir.resolve("elevate-" + token + "-paths.txt");
            var psScript = runDir.resolve("elevate-" + token + ".ps1");
            var outputFile = runDir.resolve("elevate-" + token + "-output.json");

            Files.writeString(inputFile, String.join("\n", paths.stream().map(Path::toString).toList()));

            var ps = new StringBuilder();
            ps.append("$inputFile = '").append(inputFile).append("'\n");
            ps.append("$outputFile = '").append(outputFile).append("'\n");
            ps.append("$paths = Get-Content $inputFile\n");
            ps.append("$results = @{}\n");
            ps.append("foreach ($p in $paths) {\n");
            ps.append("    if (Test-Path $p) {\n");
            ps.append("        $total = 0\n");
            ps.append("        Get-ChildItem -Path $p -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $total += $_.Length }\n");
            ps.append("        $results[$p] = $total\n");
            ps.append("    } else { $results[$p] = 0 }\n");
            ps.append("}\n");
            ps.append("$results | ConvertTo-Json | Out-File $outputFile -Encoding UTF8\n");
            Files.writeString(psScript, ps.toString());

            new ProcessBuilder("powershell", "-Command",
                    "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"" + psScript + "\"'")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();

            if (Files.exists(outputFile)) {
                var json = Files.readString(outputFile);
                var map = GSON.<Map<String, Long>>fromJson(json, new TypeToken<Map<String, Long>>(){}.getType());
                if (map != null) {
                    for (var entry : map.entrySet()) {
                        result.put(Path.of(entry.getKey()), entry.getValue() != null ? entry.getValue() : 0);
                    }
                }
            }

            try { Files.deleteIfExists(inputFile); } catch (IOException ignored) {}
            try { Files.deleteIfExists(psScript); } catch (IOException ignored) {}
            try { Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Elevated size scan failed", e);
        }
    }

    private static void calculateSizesViaElevationLinux(List<Path> paths, Map<Path, Long> result) {
        try {
            var runDir = Path.of(USER_HOME, ".filescanner", "run");
            Files.createDirectories(runDir);
            var script = runDir.resolve("elevate-" + Long.toHexString(System.nanoTime()) + ".sh");
            var sb = new StringBuilder("#!/bin/bash\n");
            for (var p : paths) {
                sb.append("S=$(du -bs \"").append(p).append("\" 2>/dev/null | cut -f1)\n");
                sb.append("echo \"").append(p).append(" $S\"\n");
            }
            Files.writeString(script, sb.toString());
            try {
                Files.setPosixFilePermissions(script, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            } catch (Exception ignored) {}

            var proc = new ProcessBuilder("pkexec", "bash", script.toString())
                    .redirectErrorStream(true)
                    .start();

            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var parts = line.trim().split(" ", 2);
                    if (parts.length == 2) {
                        try {
                            result.put(Path.of(parts[0]), Long.parseLong(parts[1]));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            proc.waitFor();
            try { Files.deleteIfExists(script); } catch (IOException ignored) {}
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Elevated size scan failed", e);
        }
    }

    private static void calculateSizesViaElevationMac(List<Path> paths, Map<Path, Long> result) {
        for (var p : paths) {
            try {
                var proc = new ProcessBuilder("osascript", "-e",
                        "do shell script \"du -bs '" + p + "' 2>/dev/null | cut -f1\" with administrator privileges")
                        .redirectErrorStream(true)
                        .start();
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        try {
                            result.put(p, Long.parseLong(line.trim()));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                proc.waitFor();
            } catch (Exception ignored) {}
        }
    }

    private static List<Target> loadTargets() {
        var all = new ArrayList<Target>();

        try (var in = SystemCleanup.class.getResourceAsStream("/cleanup-targets.json")) {
            if (in != null) {
                var root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                var mode = OS.contains("win") ? "windows" : OS.contains("mac") ? "macos" : "linux";

                addTargets(all, root, mode, mode);
                addTargets(all, root, "developer", mode);
            }
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            LOG.log(Level.WARNING, "Failed to load cleanup-targets.json", e);
        }

        mergeUserConfig(all);
        return all;
    }

    private static void addTargets(List<Target> out, com.google.gson.JsonObject root, String arrayKey, String mode) {
        if (!root.has(arrayKey)) return;
        List<TargetRecord> recs;
        try {
            recs = GSON.fromJson(root.getAsJsonArray(arrayKey), new TypeToken<List<TargetRecord>>(){}.getType());
        } catch (JsonSyntaxException e) {
            LOG.log(Level.WARNING, "Malformed cleanup targets in section " + arrayKey, e);
            return;
        }
        if (recs == null) return;

        for (var r : recs) {
            try {
                String p = pickOsPath(r, mode);
                var target = toTarget(r, p);
                if (resolvePath(target) != null) out.add(target);
            } catch (RuntimeException e) {
                // One malformed entry must not prevent the rest of the file from loading.
                LOG.log(Level.WARNING, "Skipping malformed cleanup target: " + r.name, e);
            }
        }
    }

    private static Target toTarget(TargetRecord r, String path) {
        return new Target(r.name, path, r.description, r.customCommand,
                r.contentsOnly, r.daysOld, r.extension, r.filesOnly,
                r.risk != null ? r.risk : "low");
    }

    private static String pickOsPath(TargetRecord r, String mode) {
        String p = r.path;
        if (mode.equals("windows") && r.winPath != null) p = r.winPath;
        else if (mode.equals("macos") && r.macPath != null) p = r.macPath;
        else if (mode.equals("linux") && r.linuxPath != null) p = r.linuxPath;
        return p;
    }

    private static void mergeUserConfig(List<Target> all) {
        var userFile = Path.of(USER_HOME, ".filescanner", "cleanup-targets.json");
        if (!Files.exists(userFile)) return;

        try (var reader = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
            var map = GSON.<Map<String, List<TargetRecord>>>fromJson(
                    reader, new TypeToken<Map<String, List<TargetRecord>>>(){}.getType());
            if (map == null) return;

            var mode = OS.contains("win") ? "windows" : OS.contains("mac") ? "macos" : "linux";
            for (var entry : map.entrySet()) {
                for (var r : entry.getValue()) {
                    try {
                        String p = pickOsPath(r, mode);
                        var target = toTarget(r, p);
                        if (resolvePath(target) != null) all.add(target);
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Skipping malformed user cleanup target: " + r.name, e);
                    }
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            LOG.log(Level.WARNING, "Failed to read user cleanup-targets.json", e);
        }
    }

    private static String envOr(String var, String fallback) {
        var val = System.getenv(var);
        return val != null && !val.isEmpty() ? val : fallback;
    }

    @SuppressWarnings("unused")
    private static class TargetRecord {
        String name;
        String path;
        String winPath;
        String linuxPath;
        String macPath;
        String description;
        String customCommand;
        boolean contentsOnly;
        Integer daysOld;
        String extension;
        boolean filesOnly;
        String risk;
    }
}
