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
