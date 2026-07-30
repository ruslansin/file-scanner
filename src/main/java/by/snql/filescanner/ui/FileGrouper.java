package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.util.*;
import java.util.stream.Collectors;

public final class FileGrouper {

    private FileGrouper() {}

    public record Group(String name, long totalSize, long fileCount, List<FileNode> files) {}

    public static List<Group> byFileType(FileNode root) {
        var map = new LinkedHashMap<String, List<FileNode>>();
        for (var f : FileAnalysis.flattenFiles(root)) {
            String cat = categoryName(f.getName());
            map.computeIfAbsent(cat, k -> new ArrayList<>()).add(f);
        }
        return toGroups(map);
    }

    private static String categoryName(String name) {
        String ext = ext(name);
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "bmp", "svg", "webp", "ico", "tiff", "psd", "raw", "heic" -> "Image";
            case "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp" -> "Video";
            case "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus" -> "Audio";
            case "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods" -> "Document";
            case "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "jar", "war", "iso", "dmg" -> "Archive";
            case "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php",
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
            long lastMod = lastModified(f);
            if (lastMod == 0) {
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

    private static long lastModified(FileNode file) {
        try {
            return Files.readAttributes(file.getPath(), BasicFileAttributes.class).lastModifiedTime().toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String owner(FileNode file) {
        try {
            var view = Files.getFileAttributeView(file.getPath(), FileOwnerAttributeView.class);
            if (view != null && view.getOwner() != null) return view.getOwner().getName();
        } catch (IOException ignored) {}
        return "unknown";
    }
}
