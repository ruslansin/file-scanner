package by.snql.filescanner.core.cleanup;

import by.snql.filescanner.config.Settings;
import by.snql.filescanner.core.project.ProjectType;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
     * confirmation) since not all cleanup targets carry the same blast radius. {@code actionOnly}
     * hides the generic "delete the raw path" button entirely — for locations like the Windows
     * component store (WinSxS) or the hibernation file, where deleting files directly is either
     * destructive (breaks OS servicing) or simply doesn't work, and reclaiming the space is only
     * ever safe/possible via the paired {@code customCommand} (DISM, {@code powercfg}, etc.).
     */
    public record Target(String name, String path, String description, String customCommand,
                          boolean contentsOnly, Integer daysOld, String extension, boolean filesOnly,
                          String risk, boolean actionOnly) {
        public Target(String name, String path, String description) {
            this(name, path, description, null, false, null, null, false, "low", false);
        }

        public boolean isHighRisk() { return "high".equalsIgnoreCase(risk); }
    }

    public record BuildArtifact(ProjectType projectType, String artifactName, Path path, Path projectDir) {}

    /**
     * One row of {@code docker system df} output. {@code size}/{@code reclaimable} are kept
     * as Docker's own human-readable strings (e.g. {@code "1.8GB (72%)"}) for per-row
     * display — Docker already computed them correctly from its own storage driver, so the
     * string is shown verbatim rather than reformatted through our own byte-based
     * {@code SizeFormat} and risking a rounding mismatch. See {@link #parseDockerSize} for
     * the one place these strings ARE parsed, purely to compute a best-effort aggregate.
     */
    public record DockerCategory(String type, String total, String active, String size, String reclaimable) {}

    /** Result of {@code docker system df} — {@code available=false} means Docker isn't
     *  installed, isn't running, or didn't respond in time; {@code error} explains why. */
    public record DockerUsage(boolean available, String error, List<DockerCategory> categories) {}

    /** Result of running a {@code docker} subcommand (e.g. a prune). */
    public record CommandResult(boolean success, String output) {}

    private static final java.util.regex.Pattern DOCKER_SIZE_PATTERN =
            java.util.regex.Pattern.compile("^([0-9]*\\.?[0-9]+)\\s*([a-zA-Z]*)$");

    /**
     * Best-effort parse of one of Docker's human-readable size strings (e.g.
     * {@code "1.8GB (72%)"}, {@code "212 B"}, {@code "16.43 MB"}) into bytes — used only to
     * compute the cross-section "how much space could I free" aggregate shown at the top of
     * the Developer Cleanup tab; every per-row number in the UI still shows Docker's string
     * verbatim. Docker's {@code go-units} formats sizes with decimal (1000-based, not 1024)
     * multipliers, matched here. Unknown/unparseable units contribute 0 rather than guessing,
     * so an unexpected format in some Docker version can't silently inflate the total.
     */
    public static long parseDockerSize(String raw) {
        if (raw == null) return 0;
        String s = raw.replaceAll("\\(.*?\\)", "").trim();
        var matcher = DOCKER_SIZE_PATTERN.matcher(s);
        if (!matcher.matches()) return 0;

        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }

        long multiplier = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "", "B" -> 1L;
            case "KB" -> 1_000L;
            case "MB" -> 1_000_000L;
            case "GB" -> 1_000_000_000L;
            case "TB" -> 1_000_000_000_000L;
            case "PB" -> 1_000_000_000_000_000L;
            default -> 0L;
        };
        return (long) (value * multiplier);
    }

    /** Sums {@link #parseDockerSize} over every category's reclaimable figure; 0 if Docker
     *  isn't available (nothing to add, not "unknown" — the caller treats this the same as
     *  any other section that's already finished with a known, if zero, total). */
    public static long dockerTotalReclaimable(DockerUsage usage) {
        if (!usage.available()) return 0;
        long total = 0;
        for (var cat : usage.categories()) total += parseDockerSize(cat.reclaimable());
        return total;
    }

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
                // Never descend into a dependency/build-output folder we already just
                // recorded as a whole artifact above (node_modules, vendor, target, ...):
                // a nested package.json/pom.xml inside node_modules/some-lib is somebody
                // else's published package, not "your project" — recursing into it used
                // to produce noisy duplicate "projects" and wasted a lot of time walking
                // enormous dependency trees for no benefit. ".git" is skipped outright for
                // the same reason (never contains a project, just loose objects).
                if (name.equals(".git") || ProjectType.isArtifactName(name)) continue;
                if (!name.startsWith(".") || depth < 3) {
                    findInDir(entry, depth + 1, maxDepth, result);
                }
            }
        }
    }

    // ── Docker ───────────────────────────────────────────────────────────

    private static final List<String> DOCKER_DF_FIELDS = List.of("Type", "TotalCount", "Active", "Size", "Reclaimable");

    /**
     * Runs {@code docker system df} and parses its per-category breakdown (Images,
     * Containers, Local Volumes, Build Cache). Replaces the old single blind
     * "docker system prune -a -f --volumes" button with real, current numbers so the
     * user can see what's actually reclaimable before deciding what to clean — and clean
     * just one category at a time via {@link #dockerPruneArgsFor}.
     */
    public static DockerUsage dockerDiskUsage() {
        String format = DOCKER_DF_FIELDS.stream().map(f -> "{{." + f + "}}").collect(Collectors.joining("\t"));
        var result = runDocker("system", "df", "--format", format);
        if (!result.success()) {
            String msg = result.output() == null || result.output().isBlank()
                    ? "Docker is not installed or the daemon is not running"
                    : result.output().trim();
            return new DockerUsage(false, msg, List.of());
        }

        var categories = new ArrayList<DockerCategory>();
        for (var line : result.output().split("\n")) {
            if (line.isBlank()) continue;
            var parts = line.split("\t", -1);
            if (parts.length < 5) continue;
            categories.add(new DockerCategory(parts[0], parts[1], parts[2], parts[3], parts[4]));
        }
        return new DockerUsage(true, null, categories);
    }

    /** The {@code docker ... prune} subcommand+args that reclaims just one
     *  {@code docker system df} category, or {@code null} if that category (e.g. an
     *  unrecognized future Docker version's row) has no known single-category prune. */
    public static String[] dockerPruneArgsFor(String dockerDfType) {
        return switch (dockerDfType) {
            case "Images" -> new String[]{"image", "prune", "-a", "-f"};
            case "Containers" -> new String[]{"container", "prune", "-f"};
            case "Local Volumes" -> new String[]{"volume", "prune", "-f"};
            case "Build Cache" -> new String[]{"builder", "prune", "-f"};
            default -> null;
        };
    }

    /** Runs {@code docker <args>}, waiting up to a minute (prunes on a large local
     *  registry can take a little while) and capturing combined stdout+stderr. */
    public static CommandResult runDocker(String... args) {
        try {
            var command = new ArrayList<String>();
            command.add("docker");
            command.addAll(List.of(args));
            var proc = new ProcessBuilder(command).redirectErrorStream(true).start();

            boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            if (!finished) {
                proc.destroyForcibly();
                return new CommandResult(false, "docker " + String.join(" ", args) + " timed out");
            }
            return new CommandResult(proc.exitValue() == 0, output);
        } catch (IOException e) {
            return new CommandResult(false, "Docker CLI not found on PATH: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, "Interrupted");
        }
    }

    private static String substituteVars(String raw) {
        if (OS.contains("win")) raw = raw.replace("/", "\\");
        raw = raw.replace("$HOME", USER_HOME);
        raw = raw.replace("%WINDIR%", envOr("WINDIR", "C:\\Windows"));
        raw = raw.replace("%SYSTEMDRIVE%", envOr("SystemDrive", "C:"));
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
        walkFilesSafe(root, p -> {
            if (matchesFilter(p, cutoffMillis, suffix)) result.add(p);
        });
        return result;
    }

    /**
     * Walks every regular file under {@code root}, invoking {@code onFile} for each one,
     * and simply skipping any file/directory it can't access instead of aborting the
     * whole walk. {@code Files.walk(root)} returns a *lazy* {@code Stream} that throws an
     * {@code UncheckedIOException} — not caught by {@code catch (IOException)} — from the
     * terminal operation (e.g. {@code .sum()}/{@code .forEach()}) the moment it hits an
     * inaccessible subtree anywhere in the tree; on Windows, ACL-restricted folders like
     * the legacy IE cache's {@code Content.IE5} or MSDTC's temp folder are common enough
     * that this used to reliably crash the Cleanup tab's background scan. Using
     * {@code Files.walkFileTree} with a visitor lets us return {@code CONTINUE} from
     * {@code visitFileFailed} and keep going, counting everything that IS accessible.
     */
    private static void walkFilesSafe(Path root, Consumer<Path> onFile) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) onFile.accept(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Files.walkFileTree only propagates an IOException for a failure the visitor
            // itself doesn't handle (it doesn't for one it does) — nothing to recover from.
        }
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
        if (!Files.isDirectory(path)) return safeSize(path);
        long[] total = {0};
        walkFilesSafe(path, p -> total[0] += safeSize(p));
        return total[0];
    }

    /** Result of an elevated size scan: partial/complete {@code sizes} plus a human-readable
     *  {@code error} (null on full success) so the UI can tell "the user declined the UAC/
     *  polkit/osascript admin prompt" or "the helper was blocked" apart from silent success. */
    public record ElevationResult(Map<Path, Long> sizes, String error) {
        public boolean success() { return error == null; }
    }

    public static ElevationResult calculateSizesViaElevation(List<Path> paths) {
        if (paths.isEmpty()) return new ElevationResult(Map.of(), null);
        var result = new LinkedHashMap<Path, Long>();
        String error;

        if (OS.contains("win")) {
            error = calculateSizesViaElevationWindows(paths, result);
        } else if (OS.contains("linux")) {
            error = calculateSizesViaElevationLinux(paths, result);
        } else if (OS.contains("mac")) {
            error = calculateSizesViaElevationMac(paths, result);
        } else {
            error = "Elevated scanning is not supported on this platform";
        }
        return new ElevationResult(result, error);
    }

    /** Windows error code for "the operation was canceled by the user" — the exit code
     *  {@code Start-Process -Verb RunAs} reports when the UAC consent prompt is declined. */
    private static final int ERROR_CANCELLED = 1223;

    /**
     * Elevates a small, disposable helper via {@code Start-Process -Verb RunAs} (the only way
     * to trigger a real UAC consent prompt from a non-elevated Java process — there is no
     * managed API for it). Two things bit us in earlier versions of this method and are worth
     * calling out since they're easy to reintroduce:
     * <ul>
     *   <li>The helper script used to be written to disk as a {@code .ps1} file under
     *       {@code %USERPROFILE%\.filescanner\run} and launched with {@code -File}. Corporate
     *       AppLocker/WDAC policies commonly block script execution from user-writable
     *       folders — the elevated process would then exit immediately (often before the UAC
     *       prompt even appears) with no diagnostic surfaced anywhere. Passing the script as
     *       {@code -EncodedCommand} instead runs it as inline interpreter input rather than a
     *       script *file*, which isn't subject to that class of block, nor to
     *       {@code ExecutionPolicy} (which a machine-wide Group Policy can force regardless of
     *       the {@code -ExecutionPolicy Bypass} process-scope override anyway).</li>
     *   <li>The exit code of the elevated process was never checked, so a declined UAC prompt
     *       ({@link #ERROR_CANCELLED}) looked identical to "ran fine, found nothing" from the
     *       caller's point of view. It's captured here via {@code -PassThru} on the *outer*
     *       {@code Start-Process} call and returned as {@code error} so the UI can tell the
     *       user what actually happened instead of just silently doing nothing.</li>
     * </ul>
     * Base64 (used by {@code -EncodedCommand}) never contains quotes or spaces, so — unlike
     * the old raw path interpolation — it can be embedded in the outer {@code -ArgumentList}
     * string with no escaping footguns.
     */
    private static String calculateSizesViaElevationWindows(List<Path> paths, Map<Path, Long> result) {
        Path inputFile = null, outputFile = null, logFile = null;
        try {
            var runDir = Path.of(USER_HOME, ".filescanner", "run");
            Files.createDirectories(runDir);
            String token = Long.toHexString(System.nanoTime());
            inputFile = runDir.resolve("elevate-" + token + "-paths.txt");
            outputFile = runDir.resolve("elevate-" + token + "-output.json");
            logFile = runDir.resolve("elevate-" + token + "-log.txt");

            Files.writeString(inputFile, String.join("\n", paths.stream().map(Path::toString).toList()));

            var script = new StringBuilder();
            script.append("$inputFile = '").append(psEscape(inputFile.toString())).append("'\n");
            script.append("$outputFile = '").append(psEscape(outputFile.toString())).append("'\n");
            script.append("$paths = Get-Content -LiteralPath $inputFile\n");
            script.append("$results = @{}\n");
            script.append("foreach ($p in $paths) {\n");
            script.append("    if (Test-Path -LiteralPath $p) {\n");
            script.append("        $total = 0\n");
            script.append("        Get-ChildItem -LiteralPath $p -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $total += $_.Length }\n");
            script.append("        $results[$p] = $total\n");
            script.append("    } else { $results[$p] = 0 }\n");
            script.append("}\n");
            script.append("$results | ConvertTo-Json | Out-File -LiteralPath $outputFile -Encoding UTF8\n");

            String encoded = Base64.getEncoder().encodeToString(script.toString().getBytes(StandardCharsets.UTF_16LE));

            // -PassThru surfaces the elevated process's ExitCode via $p.ExitCode even though
            // it was launched with -Verb RunAs; "exit $p.ExitCode" propagates it out to the
            // outer (non-elevated) powershell.exe's own exit code, which Java reads normally.
            var outerCommand =
                    "$p = Start-Process -FilePath powershell -Verb RunAs -Wait -PassThru " +
                    "-ArgumentList '-NoProfile -WindowStyle Hidden -EncodedCommand " + encoded + "'; " +
                    "exit $p.ExitCode";

            var proc = new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", outerCommand)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();

            boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "Timed out waiting for the elevated helper (the UAC prompt may be hidden behind another window)";
            }

            int exit = proc.exitValue();
            if (exit == ERROR_CANCELLED) {
                return "Elevation request was declined (UAC prompt cancelled)";
            }
            if (exit != 0) {
                LOG.warning("Elevated size scan exited with code " + exit + "; see " + logFile);
                return "Elevated helper exited with code " + exit;
            }

            if (Files.exists(outputFile)) {
                var json = Files.readString(outputFile);
                var map = GSON.<Map<String, Long>>fromJson(json, new TypeToken<Map<String, Long>>(){}.getType());
                if (map != null) {
                    for (var entry : map.entrySet()) {
                        result.put(Path.of(entry.getKey()), entry.getValue() != null ? entry.getValue() : 0);
                    }
                }
                return null;
            }
            return "Elevated helper finished but produced no output; see " + logFile;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Elevated size scan failed", e);
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            try { if (inputFile != null) Files.deleteIfExists(inputFile); } catch (IOException ignored) {}
            try { if (outputFile != null) Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
            try { if (logFile != null) Files.deleteIfExists(logFile); } catch (IOException ignored) {}
        }
    }

    /** Escapes a value for embedding inside a single-quoted PowerShell string literal
     *  (doubling {@code '} is PowerShell's own escape for it) — without this, a user
     *  profile path containing an apostrophe (e.g. {@code C:\Users\O'Brien}) would break
     *  the generated script's syntax. */
    private static String psEscape(String value) {
        return value.replace("'", "''");
    }

    private static String calculateSizesViaElevationLinux(List<Path> paths, Map<Path, Long> result) {
        Path script = null;
        try {
            var runDir = Path.of(USER_HOME, ".filescanner", "run");
            Files.createDirectories(runDir);
            script = runDir.resolve("elevate-" + Long.toHexString(System.nanoTime()) + ".sh");
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

            var output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    var parts = line.trim().split(" ", 2);
                    if (parts.length == 2) {
                        try {
                            result.put(Path.of(parts[0]), Long.parseLong(parts[1]));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "Timed out waiting for pkexec (the authentication prompt may need attention)";
            }
            // pkexec: 126 = auth dialog dismissed/cancelled, 127 = not authorized.
            if (proc.exitValue() == 126) return "Elevation request was declined (authentication dialog cancelled)";
            if (proc.exitValue() == 127) return "Not authorized to elevate";
            if (proc.exitValue() != 0) return "pkexec exited with code " + proc.exitValue() + (output.isEmpty() ? "" : ": " + output);
            return null;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Elevated size scan failed", e);
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            try { if (script != null) Files.deleteIfExists(script); } catch (IOException ignored) {}
        }
    }

    private static String calculateSizesViaElevationMac(List<Path> paths, Map<Path, Long> result) {
        for (var p : paths) {
            try {
                var proc = new ProcessBuilder("osascript", "-e",
                        "do shell script \"du -bs '" + p + "' 2>/dev/null | cut -f1\" with administrator privileges")
                        .redirectErrorStream(true)
                        .start();
                var output = new StringBuilder();
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line).append('\n');
                }
                boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    return "Timed out waiting for the administrator prompt";
                }
                if (proc.exitValue() != 0) {
                    // osascript's "with administrator privileges" throws error -128 when the
                    // user cancels the prompt; its text ends up in our merged output.
                    return output.toString().contains("-128") || output.toString().toLowerCase(Locale.ROOT).contains("cancel")
                            ? "Elevation request was declined (administrator prompt cancelled)"
                            : "osascript exited with code " + proc.exitValue() + (output.isEmpty() ? "" : ": " + output);
                }
                try {
                    result.put(p, Long.parseLong(output.toString().trim()));
                } catch (NumberFormatException ignored) {}
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Elevated size scan failed", e);
                return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        }
        return null;
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
                r.risk != null ? r.risk : "low", r.actionOnly);
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
        boolean actionOnly;
    }
}
