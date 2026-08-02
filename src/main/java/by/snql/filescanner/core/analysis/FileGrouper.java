package by.snql.filescanner.core.analysis;

import by.snql.filescanner.model.FileNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.util.*;
import java.util.stream.Collectors;

public final class FileGrouper {

    private FileGrouper() {}

    public record Group(String name, long totalSize, long fileCount, List<FileNode> files) {}

    public static List<Group> byFileType(FileNode root) {
        var map = new LinkedHashMap<String, List<FileNode>>();
        for (var f : FileAnalysis.flattenFiles(root)) {
            String cat = FileCategory.forFile(f.getName()).name();
            map.computeIfAbsent(cat, k -> new ArrayList<>()).add(f);
        }
        return toGroups(map);
    }

    public static List<Group> byAge(FileNode root) {
        long now = System.currentTimeMillis();
        var map = new LinkedHashMap<String, List<FileNode>>();
        map.put("Today", new ArrayList<>());
        map.put("This week", new ArrayList<>());
        map.put("This month", new ArrayList<>());
        map.put("Last 3 months", new ArrayList<>());
        map.put("Last year", new ArrayList<>());
        map.put("> 1 year", new ArrayList<>());
        map.put("Unknown", new ArrayList<>());

        for (var f : FileAnalysis.flattenFiles(root)) {
            long lastMod = f.getLastModified();
            if (lastMod <= 0) {
                map.get("Unknown").add(f);
                continue;
            }
            long age = now - lastMod;
            if (age < 24L * 3600 * 1000) map.get("Today").add(f);
            else if (age < 7L * 24 * 3600 * 1000) map.get("This week").add(f);
            else if (age < 30L * 24 * 3600 * 1000) map.get("This month").add(f);
            else if (age < 90L * 24 * 3600 * 1000) map.get("Last 3 months").add(f);
            else if (age < 365L * 24 * 3600 * 1000) map.get("Last year").add(f);
            else map.get("> 1 year").add(f);
        }
        return toGroups(map);
    }

    /**
     * Groups by file owner. This performs one filesystem attribute lookup per file
     * and is I/O-bound — callers must invoke it off the UI thread.
     */
    public static List<Group> byOwner(FileNode root) {
        var map = new LinkedHashMap<String, List<FileNode>>();
        for (var f : FileAnalysis.flattenFiles(root)) {
            String owner = owner(f);
            map.computeIfAbsent(owner, k -> new ArrayList<>()).add(f);
        }
        return toGroups(map);
    }

    private static List<Group> toGroups(Map<String, List<FileNode>> map) {
        return map.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> {
                    long size = e.getValue().stream().mapToLong(FileNode::getSize).sum();
                    return new Group(e.getKey(), size, e.getValue().size(), e.getValue());
                })
                .sorted(Comparator.comparingLong(Group::totalSize).reversed())
                .collect(Collectors.toList());
    }

    private static String owner(FileNode file) {
        try {
            var view = Files.getFileAttributeView(file.getPath(), FileOwnerAttributeView.class);
            if (view != null && view.getOwner() != null) return view.getOwner().getName();
        } catch (IOException ignored) {
        }
        return "unknown";
    }
}
