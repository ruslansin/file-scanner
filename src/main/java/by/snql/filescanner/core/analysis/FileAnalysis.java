package by.snql.filescanner.core.analysis;

import by.snql.filescanner.config.Settings;
import by.snql.filescanner.model.FileNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public final class FileAnalysis {

    private static final Set<String> EXCLUDE_DIR_NAMES = Set.of(
            ".git", "node_modules", "target", "__pycache__", ".next",
            "build", "dist", "vendor", ".venv", "venv", ".gradle"
    );

    private static final long MIN_WASTE_THRESHOLD = 1024;
    private static final int MAX_GROUPS = 50;

    private FileAnalysis() {}

    public static List<FileNode> flattenFiles(FileNode root) {
        var result = new ArrayList<FileNode>();
        collectFiles(root, result);
        return result;
    }

    private static void collectFiles(FileNode node, List<FileNode> result) {
        if (!node.isDirectory()) {
            result.add(node);
        }
        for (var child : node.getChildren()) {
            collectFiles(child, result);
        }
    }

    public static List<FileNode> largestFiles(FileNode root, int limit) {
        return flattenFiles(root).stream()
                .sorted(Comparator.comparingLong(FileNode::getSize).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    public static Map<String, FileTypeStat> fileTypeBreakdown(FileNode root) {
        var map = new TreeMap<String, FileTypeStat>();
        for (var file : flattenFiles(root)) {
            String ext = extension(file.getName());
            var stat = map.computeIfAbsent(ext, FileTypeStat::new);
            stat.count++;
            stat.totalSize += file.getSize();
        }
        return map;
    }

    public static List<DuplicateGroup> findDuplicates(FileNode root) {
        if (!Settings.get().duplicateSHA256) return List.of();

        var bySize = new HashMap<Long, List<FileNode>>();
        for (var file : flattenFiles(root)) {
            if (file.getSize() == 0) continue;
            if (file.isSymlink() || file.isHardlinkReference()) continue;
            if (isExcluded(file.getPath())) continue;
            bySize.computeIfAbsent(file.getSize(), k -> new ArrayList<>()).add(file);
        }

        var result = new ArrayList<DuplicateGroup>();

        for (var group : bySize.values()) {
            if (group.size() < 2) continue;

            var byHash = new HashMap<String, List<FileNode>>();
            for (var file : group) {
                String hash = hashFile(file);
                if (hash == null) continue;
                byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(file);
            }
            for (var sub : byHash.values()) {
                if (sub.size() >= 2) {
                    result.add(new DuplicateGroup(sub));
                }
            }
        }

        result.removeIf(g -> g.wastedSize() < MIN_WASTE_THRESHOLD);
        result.sort((a, b) -> Long.compare(b.wastedSize(), a.wastedSize()));
        if (result.size() > MAX_GROUPS) {
            result = new ArrayList<>(result.subList(0, MAX_GROUPS));
        }
        return result;
    }

    /** Longest common ancestor directory of a group of files. Cross-platform (uses Path, not string ops). */
    public static String commonPathPrefix(List<FileNode> files) {
        if (files.isEmpty()) return "";
        Path common = files.get(0).getPath().getParent();
        for (int i = 1; i < files.size() && common != null; i++) {
            common = commonAncestor(common, files.get(i).getPath().getParent());
        }
        return common != null ? common.toString() : "";
    }

    private static Path commonAncestor(Path a, Path b) {
        if (a == null || b == null) return null;
        Path candidate = a;
        while (candidate != null && !b.startsWith(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate;
    }

    private static boolean isExcluded(Path path) {
        for (var part : path) {
            if (EXCLUDE_DIR_NAMES.contains(part.toString())) return true;
        }
        return false;
    }

    public static List<FileNode> oldFiles(FileNode root, long olderThanMillis) {
        long cutoff = System.currentTimeMillis() - olderThanMillis;
        return flattenFiles(root).stream()
                .filter(f -> f.getLastModified() > 0 && f.getLastModified() < cutoff)
                .sorted(Comparator.comparingLong(FileNode::getLastModified))
                .collect(Collectors.toList());
    }

    public static List<FileNode> findEmptyDirs(FileNode root) {
        var result = new ArrayList<FileNode>();
        collectEmptyDirs(root, result);
        return result;
    }

    private static void collectEmptyDirs(FileNode node, List<FileNode> result) {
        if (node.isDirectory() && node.getChildren().isEmpty()) {
            result.add(node);
        }
        for (var child : node.getChildren()) {
            collectEmptyDirs(child, result);
        }
    }

    public static long totalDuplicateWaste(List<DuplicateGroup> groups) {
        return groups.stream().mapToLong(DuplicateGroup::wastedSize).sum();
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "(no extension)";
    }

    private static String hashFile(FileNode file) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = new DigestInputStream(Files.newInputStream(file.getPath()), digest)) {
                byte[] buf = new byte[8192];
                while (in.read(buf) != -1) {
                    // digest updates as a side effect of read()
                }
            }
            return bytesToHex(digest.digest());
        } catch (IOException e) {
            return null; // Unreadable file — exclude from duplicate matching rather than
                         // treating its path string as a fake "hash" that never collides.
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    public static class FileTypeStat {
        public final String extension;
        public int count;
        public long totalSize;

        FileTypeStat(String extension) {
            this.extension = extension;
        }

        public long getTotalSize() { return totalSize; }
        public int getCount() { return count; }
        public String getExtension() { return extension; }
    }

    public static class DuplicateGroup {
        public final List<FileNode> files;
        public final long fileSize;

        DuplicateGroup(List<FileNode> files) {
            this.files = files;
            this.fileSize = files.get(0).getSize();
        }

        public long wastedSize() {
            return fileSize * (files.size() - 1);
        }

        public List<FileNode> getFiles() { return files; }
        public long getFileSize() { return fileSize; }
    }

    public record DirEntry(int depth, String name, long size, String path, boolean last) {}

    public static List<DirEntry> directoryTree(FileNode root, int maxDepth, int maxChildren) {
        var result = new ArrayList<DirEntry>();
        collectDirs(root, 0, maxDepth, maxChildren, result, true);
        return result;
    }

    private static void collectDirs(FileNode node, int depth, int maxDepth, int maxChildren,
                                     List<DirEntry> result, boolean last) {
        if (!node.isDirectory()) return;
        result.add(new DirEntry(depth, node.getName(), node.getSize(), node.getPath().toString(), last));

        if (depth >= maxDepth) return;
        var sorted = new ArrayList<>(node.getChildren());
        sorted.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
        var dirs = sorted.stream().filter(FileNode::isDirectory).limit(maxChildren).toList();
        for (int i = 0; i < dirs.size(); i++) {
            collectDirs(dirs.get(i), depth + 1, maxDepth, maxChildren, result, i == dirs.size() - 1);
        }
    }
}
