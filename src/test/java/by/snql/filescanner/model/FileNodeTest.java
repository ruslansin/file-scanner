package by.snql.filescanner.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FileNode")
class FileNodeTest {

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("creates file node with given properties")
        void createsFileNode() {
            var path = Path.of("/tmp/test.txt");
            var node = new FileNode(path, "test.txt", false, 100);

            assertEquals(path, node.getPath());
            assertEquals("test.txt", node.getName());
            assertFalse(node.isDirectory());
            assertEquals(100, node.getSize());
            assertTrue(node.getChildren().isEmpty());
            assertTrue(node.isLeaf());
        }

        @Test
        @DisplayName("creates directory node with empty children")
        void createsDirectoryNode() {
            var path = Path.of("/tmp/dir");
            var node = new FileNode(path, "dir", true, 0);

            assertTrue(node.isDirectory());
            assertEquals(0, node.getSize());
            assertTrue(node.isLeaf());
        }
    }

    @Nested
    @DisplayName("addChild")
    class AddChild {

        @Test
        @DisplayName("adds child and updates parent size")
        void addsChildAndUpdatesSize() {
            var parent = new FileNode(Path.of("/tmp"), "tmp", true, 0);
            var child = new FileNode(Path.of("/tmp/a.txt"), "a.txt", false, 50);

            parent.addChild(child);

            assertEquals(1, parent.getChildren().size());
            assertEquals(50, parent.getSize());
            assertFalse(parent.isLeaf());
        }

        @Test
        @DisplayName("accumulates size from multiple children")
        void accumulatesSizeFromMultipleChildren() {
            var parent = new FileNode(Path.of("/tmp"), "tmp", true, 0);
            parent.addChild(new FileNode(Path.of("/tmp/a.txt"), "a.txt", false, 10));
            parent.addChild(new FileNode(Path.of("/tmp/b.txt"), "b.txt", false, 20));
            parent.addChild(new FileNode(Path.of("/tmp/c.txt"), "c.txt", false, 30));

            assertEquals(3, parent.getChildren().size());
            assertEquals(60, parent.getSize());
        }

        @Test
        @DisplayName("supports nested child addition")
        void supportsNestedChildAddition() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            var subDir = new FileNode(Path.of("/tmp"), "tmp", true, 0);
            subDir.addChild(new FileNode(Path.of("/tmp/a.txt"), "a.txt", false, 40));
            root.addChild(subDir);

            assertEquals(40, root.getSize());
        }
    }

    @Nested
    @DisplayName("sortChildren")
    class SortChildren {

        @Test
        @DisplayName("sorts children by size descending")
        void sortsBySizeDescending() {
            var parent = new FileNode(Path.of("/tmp"), "tmp", true, 0);
            parent.addChild(new FileNode(Path.of("/tmp/small.txt"), "small.txt", false, 10));
            parent.addChild(new FileNode(Path.of("/tmp/large.txt"), "large.txt", false, 100));
            parent.addChild(new FileNode(Path.of("/tmp/medium.txt"), "medium.txt", false, 50));

            parent.sortChildren();

            var children = parent.getChildren();
            assertEquals(100, children.get(0).getSize());
            assertEquals(50, children.get(1).getSize());
            assertEquals(10, children.get(2).getSize());
        }

        @Test
        @DisplayName("sorts nested children recursively")
        void sortsNestedChildrenRecursively() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            var subDir = new FileNode(Path.of("/tmp"), "tmp", true, 0);
            subDir.addChild(new FileNode(Path.of("/tmp/b.txt"), "b.txt", false, 20));
            subDir.addChild(new FileNode(Path.of("/tmp/a.txt"), "a.txt", false, 80));
            root.addChild(subDir);
            root.addChild(new FileNode(Path.of("/c.txt"), "c.txt", false, 5));

            root.sortChildren();

            var subChildren = subDir.getChildren();
            assertEquals(80, subChildren.get(0).getSize());
            assertEquals(20, subChildren.get(1).getSize());
        }
    }

    @Nested
    @DisplayName("attachChild")
    class AttachChild {

        @Test
        @DisplayName("does NOT accumulate size (unlike addChild) — for reconstructing already-sized trees")
        void doesNotAccumulateSize() {
            // Regression test for a real bug: CacheManager/SnapshotManager reconstruct a tree whose
            // sizes were already computed and stored (e.g. loaded from JSON). If attachChild summed
            // sizes the way addChild does, every ancestor's size would be inflated by its own children
            // being counted again on top of the already-correct persisted total — worse at every level
            // of nesting (a node N levels deep would count N+1 times).
            var parent = new FileNode(Path.of("/tmp"), "tmp", true, 50);
            var child = new FileNode(Path.of("/tmp/a.txt"), "a.txt", false, 50);

            parent.attachChild(child);

            assertEquals(1, parent.getChildren().size());
            assertEquals(50, parent.getSize(), "attachChild must not add the child's size again");
        }
    }

    @Nested
    @DisplayName("copyOf")
    class CopyOf {

        @Test
        @DisplayName("preserves sizes exactly across multiple levels of nesting (no double-counting)")
        void preservesSizesAcrossNesting() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            var mid = new FileNode(Path.of("/mid"), "mid", true, 0);
            var leaf = new FileNode(Path.of("/mid/file.txt"), "file.txt", false, 100);
            mid.addChild(leaf);
            root.addChild(mid);

            assertEquals(100, root.getSize());
            assertEquals(100, mid.getSize());

            var copy = FileNode.copyOf(root);

            assertEquals(100, copy.getSize(), "root size must not be inflated by the copy");
            assertEquals(100, copy.getChildren().get(0).getSize(), "nested dir size must not be inflated by the copy");
            assertEquals(100, copy.getChildren().get(0).getChildren().get(0).getSize());
        }

        @Test
        @DisplayName("preserves symlink/hardlink/buildArtifact/lastModified flags")
        void preservesFlags() {
            var node = new FileNode(Path.of("/tmp/link"), "link", false, 0);
            node.setSymlink(true);
            node.setHardlinkReference(true);
            node.setBuildArtifact(true);
            node.setLastModified(12345L);

            var copy = FileNode.copyOf(node);

            assertTrue(copy.isSymlink());
            assertTrue(copy.isHardlinkReference());
            assertTrue(copy.isBuildArtifact());
            assertEquals(12345L, copy.getLastModified());
        }

        @Test
        @DisplayName("copy is a distinct tree — mutating the source does not affect it")
        void copyIsIndependent() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            root.addChild(new FileNode(Path.of("/a.txt"), "a.txt", false, 10));

            var copy = FileNode.copyOf(root);
            root.addChild(new FileNode(Path.of("/b.txt"), "b.txt", false, 20));

            assertEquals(1, copy.getChildren().size());
            assertEquals(10, copy.getSize());
            assertEquals(2, root.getChildren().size());
            assertEquals(30, root.getSize());
        }
    }

    @Nested
    @DisplayName("isLeaf")
    class IsLeaf {

        @Test
        @DisplayName("returns true for file with no children")
        void returnsTrueForFile() {
            var file = new FileNode(Path.of("/tmp/a.txt"), "a.txt", false, 10);
            assertTrue(file.isLeaf());
        }

        @Test
        @DisplayName("returns true for empty directory")
        void returnsTrueForEmptyDirectory() {
            var dir = new FileNode(Path.of("/tmp/empty"), "empty", true, 0);
            assertTrue(dir.isLeaf());
        }

        @Test
        @DisplayName("returns false for directory with children")
        void returnsFalseForDirectoryWithChildren() {
            var dir = new FileNode(Path.of("/tmp/dir"), "dir", true, 0);
            dir.addChild(new FileNode(Path.of("/tmp/dir/a.txt"), "a.txt", false, 1));
            assertFalse(dir.isLeaf());
        }
    }

    @Nested
    @DisplayName("setSize")
    class SetSize {

        @Test
        @DisplayName("sets size explicitly")
        void setsSizeExplicitly() {
            var node = new FileNode(Path.of("/tmp/a.txt"), "a.txt", false, 100);
            node.setSize(200);
            assertEquals(200, node.getSize());
        }
    }
}
