package by.snql.filescanner.scanner;

import by.snql.filescanner.model.FileNode;
import by.snql.filescanner.ui.ProjectType;
import by.snql.filescanner.ui.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FileScanner {

    public record ScanProgress(double ratio, long filesDiscovered, long filesProcessed, long totalSizeSoFar) {}

    private volatile boolean cancelled;
    private boolean includeHidden = true;
    private final AtomicLong runningSize = new AtomicLong();

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
                                              Consumer<ScanProgress> detailCallback) {
        cancelled = false;
        runningSize.set(0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!Files.exists(root)) return null;

                    var discovered = new AtomicLong(1);
                    var processed = new AtomicLong(0);
                    var inodeMap = new HashMap<Object, Path>();

                    var node = buildTree(root, discovered, processed, progressCallback, detailCallback, inodeMap);
                    if (node != null) {
                        node.sortChildren();
                    }
                    return node;
                } catch (IOException e) {
                    throw new RuntimeException("Scan failed", e);
                }
            }, executor);
        }
    }

    private FileNode buildTree(Path path, AtomicLong discovered, AtomicLong processed,
                                Consumer<Double> progressCallback, Consumer<ScanProgress> detailCallback,
                                Map<Object, Path> inodeMap) throws IOException {
        if (cancelled) return null;

        processed.incrementAndGet();
        long proc = processed.get();
        long disc = discovered.get();
        if (proc % 500 == 0 || proc >= disc) {
            progressCallback.accept((double) proc / Math.max(disc, 1));
            if (detailCallback != null && proc % 1000 == 0) {
                detailCallback.accept(new ScanProgress(
                        (double) proc / Math.max(disc, 1), disc, proc, runningSize.get()));
            }
        }

        boolean isDir = Files.isDirectory(path);
        List<FileNode> children = new ArrayList<>();

        if (isDir) {
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
                        var fileName = entry.getFileName() != null
                                ? entry.getFileName().toString() : entry.toString();
                        long size = safeFileSize(entry);
                        var child = new FileNode(entry, fileName, false, size);
                        detectLinks(entry, child, inodeMap);
                        runningSize.addAndGet(size);
                        children.add(child);
                        processed.incrementAndGet();
                    } else {
                        var child = buildTree(entry, discovered, processed, progressCallback, detailCallback, inodeMap);
                        if (child != null) {
                            detectLinks(entry, child, inodeMap);
                            children.add(child);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        if (cancelled) return null;

        var fileName = path.getFileName() != null
                ? path.getFileName().toString() : path.toString();
        long initialSize = isDir ? 0 : safeFileSize(path);
        var node = new FileNode(path, fileName, isDir, initialSize);
        if (isDir && Settings.get().projectScanEnabled && ProjectType.isArtifactName(fileName)) {
            var parentType = ProjectType.detect(path.getParent());
            if (parentType.isPresent() && parentType.get().artifacts().contains(fileName)) {
                node.setBuildArtifact(true);
            }
        }
        for (var child : children) {
            node.addChild(child);
        }
        return node;
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
