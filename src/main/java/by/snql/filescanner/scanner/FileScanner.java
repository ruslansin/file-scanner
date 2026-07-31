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
    private FileNode rootNode;

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
        this.rootNode = null;
        cancelled = false;
        runningSize.set(0);
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(root)) return null;

                var discovered = new AtomicLong(1);
                var processed = new AtomicLong(0);
                var inodeMap = new HashMap<Object, Path>();

                rootNode = createDirNode(root, discovered, processed);
                scanChildren(root, rootNode, discovered, processed, progressCallback, inodeMap);

                if (cancelled) return null;
                rootNode.sortChildren();
                return rootNode;
            } catch (IOException e) {
                throw new RuntimeException("Scan failed", e);
            }
        }, r -> Thread.ofVirtual().start(r));
    }

    private FileNode createDirNode(Path path, AtomicLong discovered, AtomicLong processed)
            throws IOException {
        processed.incrementAndGet();
        var fileName = path.getFileName() != null
                ? path.getFileName().toString() : path.toString();
        var node = new FileNode(path, fileName, true, 0);
        if (Settings.get().projectScanEnabled && ProjectType.isArtifactName(fileName)) {
            var parentType = ProjectType.detect(path.getParent());
            if (parentType.isPresent() && parentType.get().artifacts().contains(fileName)) {
                node.setBuildArtifact(true);
            }
        }
        return node;
    }

    private void scanChildren(Path parentPath, FileNode parentNode,
                               AtomicLong discovered, AtomicLong processed,
                               Consumer<Double> progressCallback,
                               Map<Object, Path> inodeMap) throws IOException {
        if (cancelled) return;

        try (var stream = Files.list(parentPath)) {
            var entries = stream.toList();
            discovered.addAndGet(entries.size());

            for (var entry : entries) {
                if (cancelled) break;
                if (!includeHidden && isHidden(entry)) {
                    discovered.decrementAndGet();
                    continue;
                }

                var child = processEntry(entry, discovered, processed, progressCallback, inodeMap);
                if (child != null) {
                    parentNode.addChild(child);
                    emitIfTime();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private FileNode processEntry(Path path, AtomicLong discovered, AtomicLong processed,
                                   Consumer<Double> progressCallback,
                                   Map<Object, Path> inodeMap) throws IOException {
        processed.incrementAndGet();
        long proc = processed.get();
        long disc = discovered.get();
        if (proc % 500 == 0 || proc >= disc) {
            progressCallback.accept(Math.min(1.0, (double) proc / Math.max(disc, 1)));
        }

        if (!Files.isDirectory(path)) {
            var fileName = path.getFileName() != null
                    ? path.getFileName().toString() : path.toString();
            long size = safeFileSize(path);
            var node = new FileNode(path, fileName, false, size);
            detectLinks(path, node, inodeMap);
            runningSize.addAndGet(size);
            return node;
        }

        var dirNode = createDirNode(path, discovered, processed);
        scanChildren(path, dirNode, discovered, processed, progressCallback, inodeMap);
        if (cancelled) return null;
        dirNode.sortChildren();
        return dirNode;
    }

    private void emitIfTime() {
        long now = System.currentTimeMillis();
        if (realtimeCallback != null && rootNode != null && now - lastRealtime >= 300) {
            lastRealtime = now;
            realtimeCallback.accept(shallowCopy(rootNode));
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
