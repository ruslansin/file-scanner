package by.snql.filescanner.core.export;

import by.snql.filescanner.model.FileNode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves/loads/compares point-in-time snapshots of file sizes, keyed by the real
 * filesystem path of each file (not a reconstructed string), so comparisons work
 * correctly regardless of platform path-separator conventions.
 */
public class SnapshotManager {

    private static final Path SNAPSHOT_DIR = Path.of(System.getProperty("user.home"), ".filescanner", "snapshots");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SnapshotManager() {}

    public record SnapshotDiff(
            List<ChangedFile> added,
            List<ChangedFile> removed,
            List<ChangedFile> grown,
            List<ChangedFile> shrunk
    ) {
        public boolean hasChanges() {
            return !added.isEmpty() || !removed.isEmpty() || !grown.isEmpty() || !shrunk.isEmpty();
        }
    }

    public record ChangedFile(String path, long oldSize, long newSize, long delta) {}

    public static List<String> listSnapshots() {
        try {
            Files.createDirectories(SNAPSHOT_DIR);
            try (var s = Files.list(SNAPSHOT_DIR)) {
                return s.filter(f -> f.toString().endsWith(".json"))
                        .map(f -> f.getFileName().toString().replace(".json", ""))
                        .sorted(Comparator.reverseOrder())
                        .toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    public static void saveSnapshot(FileNode root, String name) throws IOException {
        Files.createDirectories(SNAPSHOT_DIR);
        var flat = new HashMap<String, Long>();
        flattenFileSizes(root, flat);

        var wrapper = new JsonObject();
        var entries = new JsonArray();
        flat.forEach((k, v) -> {
            var entry = new JsonObject();
            entry.addProperty("path", k);
            entry.addProperty("size", v);
            entries.add(entry);
        });
        wrapper.add("entries", entries);

        var file = SNAPSHOT_DIR.resolve(sanitize(name) + ".json");
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(wrapper, w);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static Map<String, Long> loadSnapshot(String name) throws IOException {
        var file = SNAPSHOT_DIR.resolve(sanitize(name) + ".json");
        var text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return parseSizeMap(GSON.fromJson(text, JsonObject.class));
        } catch (JsonSyntaxException e) {
            throw new IOException("Corrupt snapshot: " + name, e);
        }
    }

    public static void deleteSnapshot(String name) throws IOException {
        Files.deleteIfExists(SNAPSHOT_DIR.resolve(sanitize(name) + ".json"));
    }

    public static SnapshotDiff compare(FileNode current, String previousSnapshot) throws IOException {
        var prev = loadSnapshot(previousSnapshot);
        var now = new HashMap<String, Long>();
        flattenFileSizes(current, now);

        var added = new ArrayList<ChangedFile>();
        var removed = new ArrayList<ChangedFile>();
        var grown = new ArrayList<ChangedFile>();
        var shrunk = new ArrayList<ChangedFile>();

        for (var entry : now.entrySet()) {
            String path = entry.getKey();
            long curSize = entry.getValue();
            Long oldSize = prev.get(path);
            if (oldSize == null) {
                added.add(new ChangedFile(path, 0, curSize, curSize));
            } else if (curSize > oldSize) {
                grown.add(new ChangedFile(path, oldSize, curSize, curSize - oldSize));
            } else if (curSize < oldSize) {
                shrunk.add(new ChangedFile(path, oldSize, curSize, oldSize - curSize));
            }
        }

        for (var oldEntry : prev.entrySet()) {
            if (!now.containsKey(oldEntry.getKey())) {
                removed.add(new ChangedFile(oldEntry.getKey(), oldEntry.getValue(), 0, oldEntry.getValue()));
            }
        }

        added.sort((a, b) -> Long.compare(b.delta(), a.delta()));
        removed.sort((a, b) -> Long.compare(b.delta(), a.delta()));
        grown.sort((a, b) -> Long.compare(b.delta(), a.delta()));
        shrunk.sort((a, b) -> Long.compare(b.delta(), a.delta()));

        return new SnapshotDiff(added, removed, grown, shrunk);
    }

    public record SnapshotSummary(long totalAdded, long totalRemoved, long totalGrown, long totalShrunk,
                                   int addedCount, int removedCount, int grownCount, int shrunkCount) {
        public long netChange() { return totalAdded - totalRemoved + totalGrown - totalShrunk; }
    }

    public static SnapshotSummary summarize(SnapshotDiff diff) {
        long totalAdded = diff.added.stream().mapToLong(ChangedFile::delta).sum();
        long totalRemoved = diff.removed.stream().mapToLong(ChangedFile::delta).sum();
        long totalGrown = diff.grown.stream().mapToLong(ChangedFile::delta).sum();
        long totalShrunk = diff.shrunk.stream().mapToLong(ChangedFile::delta).sum();
        return new SnapshotSummary(totalAdded, totalRemoved, totalGrown, totalShrunk,
                diff.added.size(), diff.removed.size(), diff.grown.size(), diff.shrunk.size());
    }

    /** Only regular files are tracked — directories contribute no size of their own. */
    private static void flattenFileSizes(FileNode node, Map<String, Long> out) {
        if (!node.isDirectory()) {
            out.put(node.getPath().toString(), node.getSize());
        }
        for (var child : node.getChildren()) {
            flattenFileSizes(child, out);
        }
    }

    private static Map<String, Long> parseSizeMap(JsonObject root) {
        var map = new HashMap<String, Long>();
        if (root != null && root.has("entries")) {
            for (var e : root.get("entries").getAsJsonArray()) {
                var entry = e.getAsJsonObject();
                map.put(entry.get("path").getAsString(), entry.has("size") ? entry.get("size").getAsLong() : 0);
            }
        }
        return map;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
