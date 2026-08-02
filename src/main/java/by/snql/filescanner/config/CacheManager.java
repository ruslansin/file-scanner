package by.snql.filescanner.config;

import by.snql.filescanner.model.FileNode;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists the last completed scan to disk so it can be shown instantly on
 * the next application startup, before a fresh scan finishes.
 */
public class CacheManager {

    private static final Logger LOG = Logger.getLogger(CacheManager.class.getName());

    /** Bump when the on-disk schema changes so old caches are discarded instead of misread. */
    private static final int CACHE_VERSION = 2;

    private static final Path CACHE_DIR = Path.of(
            System.getProperty("user.home"), ".filescanner", "cache");
    private static final Path CACHE_FILE = CACHE_DIR.resolve("last-scan.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CacheManager() {}

    public static void saveLastScan(FileNode root) {
        try {
            Files.createDirectories(CACHE_DIR);
            var wrapper = new JsonObject();
            wrapper.addProperty("version", CACHE_VERSION);
            wrapper.addProperty("scannedAt", System.currentTimeMillis());
            wrapper.add("root", toJsonTree(root));

            var tmp = CACHE_FILE.resolveSibling(CACHE_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(wrapper));
            Files.move(tmp, CACHE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save scan cache", e);
        }
    }

    public static FileNode loadLastScan() {
        if (!Files.exists(CACHE_FILE)) return null;
        try {
            var wrapper = GSON.fromJson(Files.readString(CACHE_FILE), JsonObject.class);
            if (wrapper == null || !wrapper.has("root")) return null;
            if (!wrapper.has("version") || wrapper.get("version").getAsInt() != CACHE_VERSION) {
                return null; // Stale schema — ignore rather than risk misreading it.
            }
            return fromJson(wrapper.getAsJsonObject("root"));
        } catch (IOException | JsonSyntaxException | IllegalStateException | InvalidPathException e) {
            LOG.log(Level.WARNING, "Corrupt scan cache, ignoring", e);
            return null;
        }
    }

    private static JsonObject toJsonTree(FileNode node) {
        var obj = new JsonObject();
        obj.addProperty("name", node.getName());
        obj.addProperty("path", node.getPath().toString());
        obj.addProperty("size", node.getSize());
        obj.addProperty("directory", node.isDirectory());
        obj.addProperty("lastModified", node.getLastModified());
        if (node.isBuildArtifact()) obj.addProperty("buildArtifact", true);
        if (node.isSymlink()) obj.addProperty("symlink", true);
        if (node.isHardlinkReference()) obj.addProperty("hardlink", true);

        if (!node.getChildren().isEmpty()) {
            var children = new JsonArray();
            for (var child : node.getChildren()) {
                children.add(toJsonTree(child));
            }
            obj.add("children", children);
        }
        return obj;
    }

    private static FileNode fromJson(JsonObject obj) {
        var path = Path.of(obj.get("path").getAsString());
        var name = obj.get("name").getAsString();
        boolean isDir = obj.get("directory").getAsBoolean();
        long size = obj.get("size").getAsLong();

        var node = new FileNode(path, name, isDir, size);
        if (obj.has("lastModified")) node.setLastModified(obj.get("lastModified").getAsLong());
        if (obj.has("buildArtifact") && obj.get("buildArtifact").getAsBoolean()) node.setBuildArtifact(true);
        if (obj.has("symlink") && obj.get("symlink").getAsBoolean()) node.setSymlink(true);
        if (obj.has("hardlink") && obj.get("hardlink").getAsBoolean()) node.setHardlinkReference(true);

        if (obj.has("children")) {
            for (var child : obj.get("children").getAsJsonArray()) {
                // attachChild: sizes were already aggregated when this tree was scanned/saved.
                node.attachChild(fromJson((JsonObject) child));
            }
        }
        return node;
    }
}
