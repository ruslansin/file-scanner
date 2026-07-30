package by.snql.filescanner.ui;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.stream.Stream;

public final class SystemCleanup {

    private SystemCleanup() {}

    public record Target(String name, String path, String description, String customCommand) {
        public Target(String name, String path, String description) {
            this(name, path, description, null);
        }
    }

    public record BuildArtifact(ProjectType projectType, String artifactName, Path path, Path projectDir) {}

    private static final String OS = System.getProperty("os.name").toLowerCase();
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
                var root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
                if (root.has("scanRoots")) {
                    var arr = root.getAsJsonArray("scanRoots");
                    for (var el : arr) {
                        var resolved = resolveRaw(el.getAsString());
                        if (resolved != null && Files.exists(resolved)) roots.add(resolved);
                    }
                }
            }
        } catch (Exception e) {}

        for (var r : Settings.get().scanRoots) {
            var resolved = resolveRaw(r);
            if (resolved != null && Files.exists(resolved) && !roots.contains(resolved)) roots.add(resolved);
        }
        return roots;
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

        var type = ProjectType.detect(dir);
        if (type.isPresent()) {
            var t = type.get();
            for (var artifact : t.artifacts()) {
                var artifactPath = dir.resolve(artifact);
                if (Files.isDirectory(artifactPath)) {
                    result.add(new BuildArtifact(t, artifact, artifactPath, dir));
                }
            }
        }

        try (var stream = Files.list(dir)) {
            for (var entry : stream.toList()) {
                if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                    var name = entry.getFileName().toString();
                    if (!name.startsWith(".") || depth < 3) {
                        findInDir(entry, depth + 1, maxDepth, result);
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    public static boolean isBuildArtifact(String name) {
        try (var in = SystemCleanup.class.getResourceAsStream("/cleanup-targets.json")) {
            if (in != null) {
                var root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
                if (root.has("buildArtifacts")) {
                    var arr = root.getAsJsonArray("buildArtifacts");
                    for (var el : arr) {
                        if (el.getAsJsonObject().get("name").getAsString().equals(name)) return true;
                    }
                }
            }
        } catch (Exception e) {}
        return ProjectType.isArtifactName(name);
    }

    private static Path resolveRaw(String raw) {
        if (raw == null) return null;
        if (OS.contains("win")) raw = raw.replace("/", "\\");
        raw = raw.replace("$HOME", USER_HOME);
        raw = raw.replace("%WINDIR%", envOr("WINDIR", "C:\\Windows"));
        raw = raw.replace("%TEMP%", envOr("TEMP", System.getProperty("java.io.tmpdir")));
        raw = raw.replace("%LOCALAPPDATA%", envOr("LOCALAPPDATA", USER_HOME + "\\AppData\\Local"));
        raw = raw.replace("%APPDATA%", envOr("APPDATA", USER_HOME + "\\AppData\\Roaming"));
        raw = raw.replace("$XDG_CACHE_HOME", envOr("XDG_CACHE_HOME", USER_HOME + "/.cache"));
        return Path.of(raw);
    }

    public static long calculateSize(Target target) {
        var resolved = resolvePath(target);
        if (resolved == null || !Files.exists(resolved)) return 0;
        try {
            return walkSize(resolved);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long walkSize(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                return stream.filter(Files::isRegularFile)
                        .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                        .sum();
            }
        } else {
            return Files.size(path);
        }
    }

    public static long walkSizeSafe(Path path) {
        try {
            return walkSize(path);
        } catch (IOException e) {
            return 0;
        }
    }

    public static boolean needsElevation(Target target) {
        var resolved = resolvePath(target);
        if (resolved == null || !Files.exists(resolved)) return false;
        try {
            return pathRequiresRoot(resolved);
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean pathRequiresRoot(Path path) throws IOException {
        try {
            Files.walk(path).close();
            return false;
        } catch (java.nio.file.AccessDeniedException e) {
            return true;
        }
    }

    public static Map<Path, Long> calculateSizesViaElevation(List<Path> paths) {
        if (paths.isEmpty()) return Map.of();
        var result = new LinkedHashMap<Path, Long>();

        if (OS.contains("win")) {
            try {
                var tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
                var inputFile = tmpDir.resolve("file-scanner-paths.txt");
                var psScript = tmpDir.resolve("file-scanner-elevate.ps1");
                var outputFile = tmpDir.resolve("file-scanner-output.json");

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
                    var map = GSON.<Map<String, Long>>fromJson(json,
                            new TypeToken<Map<String, Long>>(){}.getType());
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
            }
        } else if (OS.contains("linux")) {
            try {
                var script = Path.of(System.getProperty("java.io.tmpdir"), "file-scanner-elevate.sh");
                var sb = new StringBuilder("#!/bin/bash\n");
                for (var p : paths) {
                    sb.append("S=$(du -bs \"").append(p).append("\" 2>/dev/null | cut -f1)\n");
                    sb.append("echo \"").append(p).append(" $S\"\n");
                }
                Files.writeString(script, sb.toString());
                try { Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); } catch (Exception ignored) {}

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
            }
        } else if (OS.contains("mac")) {
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

        return result;
    }

    public static Path resolvePath(Target target) {
        var raw = target.path;
        if (raw == null) return null;
        if (OS.contains("win")) raw = raw.replace("/", "\\");
        raw = raw.replace("$HOME", USER_HOME);
        raw = raw.replace("%WINDIR%", envOr("WINDIR", "C:\\Windows"));
        raw = raw.replace("%TEMP%", envOr("TEMP", System.getProperty("java.io.tmpdir")));
        raw = raw.replace("%LOCALAPPDATA%", envOr("LOCALAPPDATA", USER_HOME + "\\AppData\\Local"));
        raw = raw.replace("%APPDATA%", envOr("APPDATA", USER_HOME + "\\AppData\\Roaming"));
        raw = raw.replace("$XDG_CACHE_HOME", envOr("XDG_CACHE_HOME", USER_HOME + "/.cache"));

        var path = Path.of(raw);
        if (Files.exists(path)) return path;
        return null;
    }

    private static List<Target> loadTargets() {
        var all = new ArrayList<Target>();

        try (var in = SystemCleanup.class.getResourceAsStream("/cleanup-targets.json")) {
            if (in != null) {
                var root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();

                var mode = OS.contains("win") ? "windows" : OS.contains("mac") ? "macos" : "linux";
                var recs = GSON.<List<TargetRecord>>fromJson(
                        root.getAsJsonArray(mode),
                        new TypeToken<List<TargetRecord>>(){}.getType());

                if (recs != null) {
                    for (var r : recs) {
                        String p = pickOsPath(r, mode);
                        var target = new Target(r.name, p, r.description);
                        if (resolvePath(target) != null) all.add(target);
                    }
                }

                var devRecs = GSON.<List<TargetRecord>>fromJson(
                        root.getAsJsonArray("developer"),
                        new TypeToken<List<TargetRecord>>(){}.getType());

                if (devRecs != null) {
                    for (var r : devRecs) {
                        String p = pickOsPath(r, mode);
                        var target = new Target(r.name, p, r.description);
                        if (resolvePath(target) != null) all.add(target);
                    }
                }
            }
        } catch (Exception e) {
        }

        mergeUserConfig(all);

        return all;
    }

    private static String pickOsPath(TargetRecord r, String mode) {
        String p = r.path;
        if (mode.equals("windows") && r.winPath != null) p = r.winPath;
        else if (mode.equals("macos") && r.macPath != null) p = r.macPath;
        else if (r.linuxPath != null) p = r.linuxPath;
        return p;
    }

    private static void mergeUserConfig(List<Target> all) {
        var userFile = Path.of(USER_HOME, ".filescanner", "cleanup-targets.json");
        if (!Files.exists(userFile)) return;

        try (var reader = Files.newBufferedReader(userFile)) {
            var map = GSON.<Map<String, List<TargetRecord>>>fromJson(
                    reader, new TypeToken<Map<String, List<TargetRecord>>>(){}.getType());

            for (var key : map.keySet()) {
                for (var r : map.get(key)) {
                    String p = r.path;
                    if (OS.contains("win") && r.winPath != null) p = r.winPath;
                    else if (OS.contains("mac") && r.macPath != null) p = r.macPath;
                    else if (r.linuxPath != null) p = r.linuxPath;

                    var target = new Target(r.name, p, r.description);
                    if (resolvePath(target) != null) all.add(target);
                }
            }
        } catch (IOException e) {
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
    }
}
