package by.snql.filescanner.scanner;

import by.snql.filescanner.model.FileNode;
import by.snql.filescanner.ui.ProjectType;
import by.snql.filescanner.ui.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FileScanner {

    public record ScanProgress(double ratio, long filesDiscovered, long filesProcessed, long totalSizeSoFar) {}

    private volatile boolean cancelled;
    private boolean includeHidden = true;
    private final AtomicLong runningSize = new AtomicLong();
    private Consumer<FileNode> realtimeCallback;
    private long lastRealtime;

    public void cancel() {
        cancelled = true;
    }

    public void setIncludeHidden(boolean includeHidden) {
        this.includeHidden = includeHidden;
    }

    public CompletableFuture<FileNode> scan(Path root, Consumer<Double> progressCallback) {
        return scan(root, progressCallback, null);
    }

    public CompletableFuture<FileNode> scan(Path root, Consumer<Double> progressCallback,
                                              Consumer<FileNode> realtimeCb) {
        this.realtimeCallback = realtimeCb;
        this.lastRealtime = 0;
        cancelled = false;
        runningSize.set(0);
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(root)) return null;

                var discovered = new AtomicLong(1);
                var processed = new AtomicLong(0);
                var inodeMap = new HashMap<Object, Path>();

                var node = buildTree(root, discovered, processed, progressCallback, inodeMap);
                if (node != null) {
                    node.sortChildren();
                }
                return node;
            } catch (IOException e) {
                throw new RuntimeException("Scan failed", e);
            }
        }, r -> Thread.ofVirtual().start(r));
    }

    private FileNode buildTree(Path path, AtomicLong discovered, AtomicLong processed,
                                Consumer<Double> progressCallback,
                                Map<Object, Path> inodeMap) throws IOException {
        if (cancelled) return null;

        processed.incrementAndGet();
        long proc = processed.get();
        long disc = discovered.get();
        if (proc % 500 == 0 || proc >= disc) {
            progressCallback.accept((double) proc / Math.max(disc, 1));
        }

        boolean isDir = Files.isDirectory(path);
        var fileName = path.getFileName() != null
                ? path.getFileName().toString() : path.toString();
        long initialSize = isDir ? 0 : safeFileSize(path);
        var node = new FileNode(path, fileName, isDir, initialSize);

        if (!isDir) {
            runningSize.addAndGet(initialSize);
            detectLinks(path, node, inodeMap);
            return node;
        }

        if (isDir && Settings.get().projectScanEnabled && ProjectType.isArtifactName(fileName)) {
            var parentType = ProjectType.detect(path.getParent());
            if (parentType.isPresent() && parentType.get().artifacts().contains(fileName)) {
                node.setBuildArtifact(true);
            }
        }

        try (var stream = Files.list(path)) {
            var entries = stream.toList();
            discovered.addAndGet(entries.size());

            for (var entry : entries) {
                if (cancelled) break;
                if (!includeHidden && isHidden(entry)) {
                    discovered.decrementAndGet();
                    continue;
                }

                if (!Files.isDirectory(entry)) {
                    var childName = entry.getFileName() != null
                            ? entry.getFileName().toString() : entry.toString();
                    long size = safeFileSize(entry);
                    var child = new FileNode(entry, childName, false, size);
                    detectLinks(entry, child, inodeMap);
                    runningSize.addAndGet(size);
                    node.addChild(child);
                    processed.incrementAndGet();
                } else {
                    var child = buildTree(entry, discovered, processed, progressCallback, inodeMap);
                    if (child != null) {
                        detectLinks(entry, child, inodeMap);
                        node.addChild(child);
                    }
                }
            }
        } catch (IOException ignored) {
        }

        if (cancelled) return null;

        emitRealtime(node);

        return node;
    }

    private void emitRealtime(FileNode node) {
        long now = System.currentTimeMillis();
        if (realtimeCallback != null && now - lastRealtime >= 300) {
            lastRealtime = now;
            var copy = shallowCopy(node);
            realtimeCallback.accept(copy);
        }
    }

    private static FileNode shallowCopy(FileNode node) {
        var copy = new FileNode(node.getPath(), node.getName(), node.isDirectory(), node.getSize());
        if (node.isBuildArtifact()) copy.setBuildArtifact(true);
        if (node.isSymlink()) copy.setSymlink(true);
        if (node.isHardlinkReference()) copy.setHardlinkReference(true);
        for (var child : node.getChildren()) {
            copy.addChild(shallowCopy(child));
        }
        return copy;
    }

    private static void detectLinks(Path path, FileNode node, Map<Object, Path> inodeMap) {
        try {
            if (Files.isSymbolicLink(path)) {
                node.setSymlink(true);
                node.setSize(0);
                return;
            }
            var attr = Files.readAttributes(path, BasicFileAttributes.class);
            Object key = attr.fileKey();
            if (key != null) {
                if (inodeMap.containsKey(key)) {
                    node.setHardlinkReference(true);
                    node.setSize(0);
                } else {
                    inodeMap.put(key, path);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return path.getFileName() != null
                    && path.getFileName().toString().startsWith(".");
        }
    }

    private static long safeFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }
}
