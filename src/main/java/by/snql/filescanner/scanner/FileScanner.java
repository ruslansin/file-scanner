package by.snql.filescanner.scanner;

import by.snql.filescanner.model.FileNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class FileScanner {

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public CompletableFuture<FileNode> scan(Path root, Consumer<Double> progressCallback) {
        cancelled = false;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!Files.exists(root)) return null;

                    long[] totalDirs = {0};
                    long[] processed = {0};

                    var rootSize = countAll(root, totalDirs);
                    totalDirs[0] = Math.max(totalDirs[0], 1);

                    var node = buildTree(root, processed, totalDirs, progressCallback);
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

    private long countAll(Path dir, long[] count) throws IOException {
        count[0]++;
        try (var stream = Files.list(dir)) {
            var entries = stream.toList();
            for (var entry : entries) {
                if (cancelled) return 0;
                if (Files.isDirectory(entry)) {
                    countAll(entry, count);
                }
            }
        } catch (IOException ignored) {
        }
        return count[0];
    }

    private FileNode buildTree(Path path, long[] processed, long[] total,
                                Consumer<Double> progressCallback) throws IOException {
        if (cancelled) return null;

        processed[0]++;
        if (processed[0] % 50 == 0 || processed[0] == total[0]) {
            progressCallback.accept((double) processed[0] / total[0]);
        }

        boolean isDir = Files.isDirectory(path);
        List<FileNode> children = new ArrayList<>();

        if (isDir) {
            try (var stream = Files.list(path)) {
                for (var entry : stream.toList()) {
                    if (cancelled) break;
                    var child = buildTree(entry, processed, total, progressCallback);
                    if (child != null) {
                        children.add(child);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        var fileName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        long initialSize = isDir ? 0 : safeFileSize(path);
        var node = new FileNode(path, fileName, isDir, initialSize);
        for (var child : children) {
            node.addChild(child);
        }
        return node;
    }

    private static long safeFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }
}
