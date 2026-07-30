package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SnapshotManager {

    private static final Path SNAPSHOT_DIR = Path.of(System.getProperty("user.home"), ".filescanner", "snapshots");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record SnapshotDiff(
            List<FileNode> added,
            List<FileNode> removed,
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
        flattenSizes(root, "", flat);
        var wrapper = new JsonObject();
        var entries = new com.google.gson.JsonArray();
        flat.forEach((k, v) -> {
            var entry = new JsonObject();
            entry.addProperty("path", k);
            entry.addProperty("size", v);
            entries.add(entry);
        });
        wrapper.add("entries", entries);
        var file = SNAPSHOT_DIR.resolve(sanitize(name) + ".json");
        try (var w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(wrapper, w);
        }
    }

    public static Map<String, Long> loadSnapshot(String name) throws IOException {
        var file = SNAPSHOT_DIR.resolve(sanitize(name) + ".json");
        var text = Files.readString(file, StandardCharsets.UTF_8);
        return parseSizeMap(GSON.fromJson(text, JsonObject.class));
    }

    public static void deleteSnapshot(String name) throws IOException {
        var file = SNAPSHOT_DIR.resolve(sanitize(name) + ".json");
        Files.deleteIfExists(file);
    }

    public static SnapshotDiff compare(FileNode current, String previousSnapshot) throws IOException {
        var prev = loadSnapshot(previousSnapshot);
        var now = new HashMap<String, Long>();
        flattenSizes(current, "", now);

        var added = new ArrayList<FileNode>();
        var removed = new ArrayList<FileNode>();
        var grown = new ArrayList<ChangedFile>();
        var shrunk = new ArrayList<ChangedFile>();

        for (var entry : now.entrySet()) {
            String path = entry.getKey();
            long curSize = entry.getValue();
            Long oldSize = prev.get(path);
            if (oldSize == null) {
                added.add(new FileNode(Path.of(path), Path.of(path).getFileName().toString(), Files.isDirectory(Path.of(path)), curSize));
            } else if (curSize > oldSize) {
                grown.add(new ChangedFile(path, oldSize, curSize, curSize - oldSize));
            } else if (curSize < oldSize) {
                shrunk.add(new ChangedFile(path, oldSize, curSize, oldSize - curSize));
            }
        }

        for (var oldPath : prev.keySet()) {
            if (!now.containsKey(oldPath)) {
                removed.add(new FileNode(Path.of(oldPath), Path.of(oldPath).getFileName().toString(), false, oldPath.isEmpty() ? 0 : (prev.get(oldPath))));
            }
        }

        added.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
        removed.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
        grown.sort((a, b) -> Long.compare(b.delta(), a.delta()));
        shrunk.sort((a, b) -> Long.compare(b.delta(), a.delta()));

        return new SnapshotDiff(added, removed, grown, shrunk);
    }

    public record SnapshotSummary(long totalAdded, long totalRemoved, long totalGrown, long totalShrunk,
                                   int addedCount, int removedCount, int grownCount, int shrunkCount) {
        public long netChange() { return totalAdded - totalRemoved + totalGrown - totalShrunk; }
    }

    public static SnapshotSummary summarize(SnapshotDiff diff) {
        long totalAdded = diff.added.stream().mapToLong(FileNode::getSize).sum();
        long totalRemoved = diff.removed.stream().mapToLong(FileNode::getSize).sum();
        long totalGrown = diff.grown.stream().mapToLong(ChangedFile::delta).sum();
        long totalShrunk = diff.shrunk.stream().mapToLong(ChangedFile::delta).sum();
        return new SnapshotSummary(totalAdded, totalRemoved, totalGrown, totalShrunk,
                diff.added.size(), diff.removed.size(), diff.grown.size(), diff.shrunk.size());
    }

    private static void flattenSizes(FileNode node, String prefix, Map<String, Long> out) {
        String full = prefix.isEmpty() ? node.getPath().toString() : prefix + "/" + node.getName();
        out.put(full, node.isDirectory() ? 0 : node.getSize());
        for (var child : node.getChildren()) {
            flattenSizes(child, full, out);
        }
    }

    private static Map<String, Long> parseSizeMap(JsonObject root) {
        var map = new HashMap<String, Long>();
        if (root.has("entries")) {
            for (var e : root.get("entries").getAsJsonArray()) {
                if (e instanceof JsonObject entry) {
                    map.put(entry.get("path").getAsString(), entry.has("size") ? entry.get("size").getAsLong() : 0);
                }
            }
        }
        return map;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
