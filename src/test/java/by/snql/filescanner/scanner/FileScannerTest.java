package by.snql.filescanner.scanner;

import by.snql.filescanner.model.FileNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FileScanner")
class FileScannerTest {

    @Nested
    @DisplayName("scan")
    class Scan {

        @Test
        @DisplayName("scans empty directory")
        void scansEmptyDirectory(@TempDir Path tempDir) throws Exception {
            var scanner = new FileScanner();
            var progress = new ArrayList<Double>();

            var root = scanner.scan(tempDir, progress::add).get(5, TimeUnit.SECONDS);

            assertNotNull(root);
            assertTrue(root.isDirectory());
            assertEquals(0, root.getSize());
            assertTrue(root.isLeaf());
        }

        @Test
        @DisplayName("scans directory with files")
        void scansDirectoryWithFiles(@TempDir Path tempDir) throws Exception {
            Files.writeString(tempDir.resolve("a.txt"), "hello");
            Files.writeString(tempDir.resolve("b.txt"), "world!");

            var scanner = new FileScanner();

            var root = scanner.scan(tempDir, p -> {}).get(5, TimeUnit.SECONDS);

            assertNotNull(root);
            assertTrue(root.isDirectory());
            assertTrue(root.getSize() > 0);
            assertFalse(root.isLeaf());
            assertEquals(2, root.getChildren().size());
        }

        @Test
        @DisplayName("scans nested directory structure")
        void scansNestedDirectories(@TempDir Path tempDir) throws Exception {
            var subDir = Files.createDirectory(tempDir.resolve("sub"));
            Files.writeString(subDir.resolve("inner.txt"), "data");
            Files.writeString(tempDir.resolve("outer.txt"), "more data");

            var scanner = new FileScanner();

            var root = scanner.scan(tempDir, p -> {}).get(5, TimeUnit.SECONDS);

            assertNotNull(root);
            assertEquals(2, root.getChildren().size());

            long totalSize = root.getSize();
            long childrenSum = root.getChildren().stream().mapToLong(FileNode::getSize).sum();
            assertEquals(totalSize, childrenSum);
        }

        @Test
        @DisplayName("reports progress during scan")
        void reportsProgress(@TempDir Path tempDir) throws Exception {
            for (int i = 0; i < 5; i++) {
                Files.createDirectory(tempDir.resolve("dir" + i));
            }

            var scanner = new FileScanner();
            var progressValues = new ArrayList<Double>();

            scanner.scan(tempDir, progressValues::add).get(5, TimeUnit.SECONDS);

            assertFalse(progressValues.isEmpty());
            assertEquals(1.0, progressValues.get(progressValues.size() - 1), 0.01);
        }

        @Test
        @DisplayName("handles inaccessible files gracefully")
        void handlesInaccessibleFiles(@TempDir Path tempDir) throws Exception {
            var scanner = new FileScanner();

            var root = scanner.scan(tempDir.resolve("nonexistent"), p -> {}).get(5, TimeUnit.SECONDS);

            assertNull(root);
        }

        @Test
        @DisplayName("cancel does not throw exceptions")
        void cancelDoesNotThrow(@TempDir Path tempDir) throws Exception {
            for (int i = 0; i < 500; i++) {
                Files.createDirectory(tempDir.resolve("dir" + i));
            }

            var scanner = new FileScanner();

            var future = scanner.scan(tempDir, p -> {});
            scanner.cancel();

            try {
                future.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Cancel should not cause exceptions", e);
            }
        }

        @Test
        @DisplayName("sortChildren is called after scan")
        void sortsChildrenAfterScan(@TempDir Path tempDir) throws Exception {
            Files.writeString(tempDir.resolve("small.txt"), "x");
            Files.writeString(tempDir.resolve("large.txt"), "x".repeat(1000));

            var scanner = new FileScanner();

            var root = scanner.scan(tempDir, p -> {}).get(5, TimeUnit.SECONDS);

            var children = root.getChildren();
            for (int i = 0; i < children.size() - 1; i++) {
                assertTrue(children.get(i).getSize() >= children.get(i + 1).getSize());
            }
        }

        @Test
        @DisplayName("file nodes have correct size equal to file length")
        void fileNodesHaveCorrectSize(@TempDir Path tempDir) throws Exception {
            Files.writeString(tempDir.resolve("data.txt"), "A".repeat(42));

            var scanner = new FileScanner();

            var root = scanner.scan(tempDir, p -> {}).get(5, TimeUnit.SECONDS);

            assertEquals(42, root.getChildren().get(0).getSize());
        }

        @Test
        @DisplayName("concurrent scans on different roots are independent")
        void concurrentScansIndependent(@TempDir Path tempDir) throws Exception {
            var dirA = Files.createDirectory(tempDir.resolve("a"));
            var dirB = Files.createDirectory(tempDir.resolve("b"));
            Files.writeString(dirA.resolve("file.txt"), "A");
            Files.writeString(dirB.resolve("file.txt"), "BB");

            var scanner = new FileScanner();

            var futureA = scanner.scan(dirA, p -> {});
            var futureB = scanner.scan(dirB, p -> {});

            var rootA = futureA.get(5, TimeUnit.SECONDS);
            var rootB = futureB.get(5, TimeUnit.SECONDS);

            assertEquals(1, rootA.getSize());
            assertEquals(2, rootB.getSize());
        }

        @Test
        @DisplayName("handles symlinks without following them")
        void handlesSymlinks(@TempDir Path tempDir) throws Exception {
            var realDir = Files.createDirectory(tempDir.resolve("real"));
            Files.writeString(realDir.resolve("file.txt"), "content");

            try {
                Files.createSymbolicLink(tempDir.resolve("link"), realDir);

                var scanner = new FileScanner();
                var root = scanner.scan(tempDir, p -> {}).get(5, TimeUnit.SECONDS);

                assertNotNull(root);

                var linkChild = root.getChildren().stream()
                        .filter(c -> "link".equals(c.getName()))
                        .findFirst()
                        .orElse(null);
                assertNotNull(linkChild);
            } catch (UnsupportedOperationException e) {
            }
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("scan after cancel works normally")
        void cancelBeforeScanDoesNotAffect(@TempDir Path tempDir) throws Exception {
            var scanner = new FileScanner();
            scanner.cancel();

            var root = scanner.scan(tempDir, p -> {}).get(5, TimeUnit.SECONDS);
            assertNotNull(root);
        }
    }
}
