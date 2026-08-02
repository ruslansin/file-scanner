package by.snql.filescanner.scanner;

import by.snql.filescanner.core.project.ProjectType;
import by.snql.filescanner.model.FileNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Recursively walks a directory tree on virtual threads, building a {@link FileNode} tree.
 * Each call to {@link #scan} runs in its own isolated {@link Session} so that concurrent
 * or repeated scans on the same {@code FileScanner} instance never share mutable state.
 */
public class FileScanner {

    /** Scan behaviour flags. Immutable — replace via the setters, never mutate. */
    public record ScanOptions(boolean includeHidden, boolean detectBuildArtifacts) {
        public static ScanOptions defaults() {
            return new ScanOptions(true, true);
        }
    }

    private volatile ScanOptions options = ScanOptions.defaults();
    private volatile Session currentSession;

    public void setIncludeHidden(boolean includeHidden) {
        options = new ScanOptions(includeHidden, options.detectBuildArtifacts());
    }

    public void setDetectBuildArtifacts(boolean detect) {
        options = new ScanOptions(options.includeHidden(), detect);
    }

    /** Cancels the most recently started scan on this instance, if still running. */
    public void cancel() {
        var session = currentSession;
        if (session != null) session.cancelled = true;
    }

    public CompletableFuture<FileNode> scan(Path root, Consumer<Double> progressCallback) {
        return scan(root, progressCallback, null);
    }

    public CompletableFuture<FileNode> scan(Path root, Consumer<Double> progressCallback,
                                             Consumer<FileNode> realtimeCb) {
        var session = new Session(options, realtimeCb);
        currentSession = session;

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(root)) return null;

                var discovered = new AtomicLong(1);
                var processed = new AtomicLong(0);

                var rootNode = createDirNode(session, root, discovered, processed);
                session.rootNode = rootNode;
                scanChildren(session, root, rootNode, discovered, processed, progressCallback);

                if (session.cancelled) return null;
                rootNode.sortChildren();
                return rootNode;
            } catch (IOException e) {
                throw new RuntimeException("Scan failed", e);
            }
        }, r -> Thread.ofVirtual().start(r));
    }

    private static final class Session {
        final ScanOptions options;
        final Consumer<FileNode> realtimeCallback;
        final Map<Object, Path> inodeMap = new HashMap<>();
        final Set<Object> visitedDirs = new HashSet<>();
        volatile boolean cancelled;
        volatile FileNode rootNode;
        long lastRealtimeEmit;

        Session(ScanOptions options, Consumer<FileNode> realtimeCallback) {
            this.options = options;
            this.realtimeCallback = realtimeCallback;
        }
    }

    private FileNode createDirNode(Session session, Path path, AtomicLong discovered, AtomicLong processed)
            throws IOException {
        processed.incrementAndGet();
        var fileName = path.getFileName() != null
                ? path.getFileName().toString() : path.toString();
        var node = new FileNode(path, fileName, true, 0);
        node.setLastModified(safeLastModified(path));
        if (session.options.detectBuildArtifacts() && ProjectType.isArtifactName(fileName)) {
            var parentType = ProjectType.detect(path.getParent());
            if (parentType.isPresent() && parentType.get().artifacts().contains(fileName)) {
                node.setBuildArtifact(true);
            }
        }
        return node;
    }

    private void scanChildren(Session session, Path parentPath, FileNode parentNode,
                               AtomicLong discovered, AtomicLong processed,
                               Consumer<Double> progressCallback) throws IOException {
        if (session.cancelled) return;

        try (var stream = Files.list(parentPath)) {
            var entries = stream.toList();
            discovered.addAndGet(entries.size());

            for (var entry : entries) {
                if (session.cancelled) break;
                if (!session.options.includeHidden() && isHidden(entry)) {
                    discovered.decrementAndGet();
                    continue;
                }

                var child = processEntry(session, entry, discovered, processed, progressCallback);
                if (child != null) {
                    parentNode.addChild(child);
                    emitIfTime(session);
                }
            }
        } catch (IOException ignored) {
            // Permission-denied or similar — skip this directory's contents.
        }
    }

    private FileNode processEntry(Session session, Path path, AtomicLong discovered, AtomicLong processed,
                                   Consumer<Double> progressCallback) throws IOException {
        processed.incrementAndGet();
        long proc = processed.get();
        long disc = discovered.get();
        if (proc % 500 == 0 || proc >= disc) {
            progressCallback.accept(Math.min(1.0, (double) proc / Math.max(disc, 1)));
        }

        boolean symlink = Files.isSymbolicLink(path);
        // Never follow symlinks into directories: avoids infinite cycles and double-counting.
        boolean isDir = !symlink && Files.isDirectory(path);

        if (!isDir) {
            var fileName = path.getFileName() != null
                    ? path.getFileName().toString() : path.toString();
            long size = symlink ? 0 : safeFileSize(path);
            var node = new FileNode(path, fileName, false, size);
            node.setLastModified(safeLastModified(path));
            if (symlink) {
                node.setSymlink(true);
            } else {
                detectHardlink(path, node, session.inodeMap);
            }
            return node;
        }

        // Guard against directory cycles reachable via junctions / bind mounts.
        Object dirKey = dirKey(path);
        if (dirKey != null && !session.visitedDirs.add(dirKey)) {
            var fileName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
            var node = new FileNode(path, fileName, true, 0);
            node.setHardlinkReference(true);
            return node;
        }

        var dirNode = createDirNode(session, path, discovered, processed);
        scanChildren(session, path, dirNode, discovered, processed, progressCallback);
        if (session.cancelled) return null;
        dirNode.sortChildren();
        return dirNode;
    }

    private void emitIfTime(Session session) {
        long now = System.currentTimeMillis();
        if (session.realtimeCallback != null && session.rootNode != null
                && now - session.lastRealtimeEmit >= 300) {
            session.lastRealtimeEmit = now;
            // Hand off a snapshot copy, not the live tree — the UI thread will read it
            // while this scan thread keeps mutating the real tree concurrently.
            session.realtimeCallback.accept(FileNode.copyOf(session.rootNode));
        }
    }

    private static void detectHardlink(Path path, FileNode node, Map<Object, Path> inodeMap) {
        try {
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

    private static Object dirKey(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
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

    private static long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
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
