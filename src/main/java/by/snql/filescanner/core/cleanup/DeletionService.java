package by.snql.filescanner.core.cleanup;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized, honest deletion. Every caller (tree view, duplicates, cleanup
 * targets, project artifacts) goes through this class instead of its own
 * {@code catch (IOException ignored)} loop, so:
 *  <ul>
 *    <li>deletion defaults to the OS trash/recycle bin, not permanent removal;</li>
 *    <li>failures are reported per-path instead of silently swallowed.</li>
 *  </ul>
 */
public final class DeletionService {

    private static final Logger LOG = Logger.getLogger(DeletionService.class.getName());

    private DeletionService() {}

    /** @param deleted paths that were successfully removed (trashed or permanently deleted)
     *  @param errors  paths that failed, mapped to a human-readable reason */
    public record DeletionResult(List<Path> deleted, Map<Path, String> errors) {
        public boolean allSucceeded() { return errors.isEmpty(); }
        public int failureCount() { return errors.size(); }
    }

    public static boolean isTrashSupported() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
    }

    /**
     * Deletes each given top-level path (file or directory tree) as one unit.
     * @param moveToTrash if true, uses the OS trash/recycle bin; if the OS does not
     *                     support it, every path fails with a clear error instead of
     *                     silently deleting permanently.
     */
    public static DeletionResult delete(List<Path> paths, boolean moveToTrash) {
        var deleted = new ArrayList<Path>();
        var errors = new LinkedHashMap<Path, String>();

        boolean trashSupported = moveToTrash && isTrashSupported();
        if (moveToTrash && !trashSupported) {
            for (var p : paths) errors.put(p, "Trash is not supported on this system");
            return new DeletionResult(deleted, errors);
        }

        for (var path : paths) {
            try {
                if (!Files.exists(path)) {
                    errors.put(path, "Does not exist");
                    continue;
                }
                if (moveToTrash) {
                    if (Desktop.getDesktop().moveToTrash(path.toFile())) {
                        deleted.add(path);
                    } else {
                        errors.put(path, "OS declined to move to trash");
                    }
                } else {
                    deletePermanently(path);
                    deleted.add(path);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to delete " + path, e);
                errors.put(path, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        return new DeletionResult(deleted, errors);
    }

    /** Deletes the immediate children of {@code dir}, keeping {@code dir} itself. */
    public static DeletionResult deleteContents(Path dir, boolean moveToTrash) {
        if (dir == null || !Files.isDirectory(dir)) {
            return new DeletionResult(List.of(), Map.of());
        }
        try (var stream = Files.list(dir)) {
            return delete(stream.toList(), moveToTrash);
        } catch (IOException e) {
            var errors = new LinkedHashMap<Path, String>();
            errors.put(dir, "Could not list directory: " + e.getMessage());
            return new DeletionResult(List.of(), errors);
        }
    }

    /**
     * Permanently deletes a single path (file or whole directory tree). Throws on the
     * first failure encountered while walking, after already removing any deeper
     * entries that succeeded — callers see the exception via {@link #delete}.
     */
    private static void deletePermanently(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            Files.delete(path);
            return;
        }
        try (var stream = Files.walk(path)) {
            var toDelete = stream.sorted(Comparator.reverseOrder()).toList();
            FileSystemException firstError = null;
            for (var p : toDelete) {
                try {
                    Files.delete(p);
                } catch (FileSystemException e) {
                    if (firstError == null) firstError = e;
                }
            }
            if (firstError != null) throw firstError;
        }
    }
}
