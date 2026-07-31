package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CacheManager {

    private static final Path CACHE_DIR = Path.of(
            System.getProperty("user.home"), ".filescanner", "cache");
    private static final Path CACHE_FILE = CACHE_DIR.resolve("last-scan.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void saveLastScan(FileNode root) {
        try {
            Files.createDirectories(CACHE_DIR);
            var json = toJsonTree(root);
            Files.writeString(CACHE_FILE, GSON.toJson(json));
        } catch (IOException ignored) {
        }
    }

    public static FileNode loadLastScan() {
        if (!Files.exists(CACHE_FILE)) return null;
        try {
            var json = GSON.fromJson(Files.readString(CACHE_FILE), JsonObject.class);
            return fromJson(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonObject toJsonTree(FileNode node) {
        var obj = new JsonObject();
        obj.addProperty("name", node.getName());
        obj.addProperty("path", node.getPath().toString());
        obj.addProperty("size", node.getSize());
        obj.addProperty("directory", node.isDirectory());
        obj.addProperty("buildArtifact", node.isBuildArtifact());
        obj.addProperty("symlink", node.isSymlink());
        obj.addProperty("hardlink", node.isHardlinkReference());

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

        if (obj.has("buildArtifact") && obj.get("buildArtifact").getAsBoolean()) {
            node.setBuildArtifact(true);
        }
        if (obj.has("symlink") && obj.get("symlink").getAsBoolean()) {
            node.setSymlink(true);
        }
        if (obj.has("hardlink") && obj.get("hardlink").getAsBoolean()) {
            node.setHardlinkReference(true);
        }

        if (obj.has("children")) {
            for (var child : obj.get("children").getAsJsonArray()) {
                node.addChild(fromJson((JsonObject) child));
            }
        }
        return node;
    }
}
