package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TreemapLayout")
class TreemapLayoutTest {

    @Nested
    @DisplayName("compute")
    class Compute {

        @Test
        @DisplayName("returns empty array for null root")
        void returnsEmptyForNullRoot() {
            var rects = TreemapLayout.compute(null, 100, 100);
            assertEquals(0, rects.length);
        }

        @Test
        @DisplayName("returns empty array for zero dimensions")
        void returnsEmptyForZeroDimensions() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            assertEquals(0, TreemapLayout.compute(root, 0, 0).length);
            assertEquals(0, TreemapLayout.compute(root, 0, 100).length);
            assertEquals(0, TreemapLayout.compute(root, 100, 0).length);
        }

        @Test
        @DisplayName("returns single rect for leaf node")
        void returnsSingleRectForLeaf() {
            var file = new FileNode(Path.of("/tmp/a.txt"), "a.txt", false, 42);

            var rects = TreemapLayout.compute(file, 200, 100);

            assertEquals(1, rects.length);
            assertEquals(0, rects[0].x());
            assertEquals(0, rects[0].y());
            assertEquals(200, rects[0].w());
            assertEquals(100, rects[0].h());
        }

        @Test
        @DisplayName("returns single rect for empty directory")
        void returnsSingleRectForEmptyDirectory() {
            var dir = new FileNode(Path.of("/tmp/empty"), "empty", true, 0);

            var rects = TreemapLayout.compute(dir, 200, 100);

            assertEquals(1, rects.length);
        }

        @Test
        @DisplayName("lays out two children horizontally when wider than tall")
        void laysOutTwoChildrenHorizontally() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            root.addChild(new FileNode(Path.of("/a.txt"), "a.txt", false, 50));
            root.addChild(new FileNode(Path.of("/b.txt"), "b.txt", false, 50));

            var rects = TreemapLayout.compute(root, 400, 200);

            assertEquals(3, rects.length);

            var child1 = rects[1];
            var child2 = rects[2];

            assertTrue(child1.w() > 0 && child2.w() > 0);
            assertTrue(child1.h() > 0 && child2.h() > 0);

            assertEquals(child1.h(), child2.h(), 1.0);
        }

        @Test
        @DisplayName("child rects are fully inside parent rect")
        void childRectsInsideParent() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            root.addChild(new FileNode(Path.of("/a.txt"), "a.txt", false, 30));
            root.addChild(new FileNode(Path.of("/b.txt"), "b.txt", false, 70));

            var rects = TreemapLayout.compute(root, 400, 300);

            var parent = rects[0];
            for (int i = 1; i < rects.length; i++) {
                var child = rects[i];
                assertTrue(child.x() >= parent.x());
                assertTrue(child.y() >= parent.y());
                assertTrue(child.x() + child.w() <= parent.x() + parent.w() + 1);
                assertTrue(child.y() + child.h() <= parent.y() + parent.h() + 1);
            }
        }

        @Test
        @DisplayName("larger child gets larger area")
        void largerChildGetsLargerArea() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            root.addChild(new FileNode(Path.of("/big.txt"), "big.txt", false, 90));
            root.addChild(new FileNode(Path.of("/small.txt"), "small.txt", false, 10));

            var rects = TreemapLayout.compute(root, 400, 300);

            var bigArea = rects[1].w() * rects[1].h();
            var smallArea = rects[2].w() * rects[2].h();
            assertTrue(bigArea > smallArea);
        }

        @Test
        @DisplayName("no overlapping child rectangles")
        void noOverlappingChildren() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            root.addChild(new FileNode(Path.of("/a.txt"), "a.txt", false, 33));
            root.addChild(new FileNode(Path.of("/b.txt"), "b.txt", false, 33));
            root.addChild(new FileNode(Path.of("/c.txt"), "c.txt", false, 34));

            var rects = TreemapLayout.compute(root, 400, 300);

            for (int i = 1; i < rects.length; i++) {
                for (int j = i + 1; j < rects.length; j++) {
                    assertFalse(overlap(rects[i], rects[j]),
                            "rects " + i + " and " + j + " should not overlap");
                }
            }
        }

        @Test
        @DisplayName("lays out deeply nested directories")
        void laysOutDeeplyNested() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            var mid = new FileNode(Path.of("/mid"), "mid", true, 0);
            var inner = new FileNode(Path.of("/mid/inner"), "inner", true, 0);
            inner.addChild(new FileNode(Path.of("/mid/inner/file.txt"), "file.txt", false, 100));
            mid.addChild(inner);
            root.addChild(mid);

            var rects = TreemapLayout.compute(root, 400, 300);

            assertTrue(rects.length >= 4);
        }
    }

    @Nested
    @DisplayName("countDescendants")
    class CountDescendants {

        @Test
        @DisplayName("leaf has one descendant")
        void leafHasOneDescendant() {
            var file = new FileNode(Path.of("/a.txt"), "a.txt", false, 10);
            assertEquals(1, TreemapLayout.countDescendants(file));
        }

        @Test
        @DisplayName("empty directory has one descendant")
        void emptyDirectoryHasOneDescendant() {
            var dir = new FileNode(Path.of("/empty"), "empty", true, 0);
            assertEquals(1, TreemapLayout.countDescendants(dir));
        }

        @Test
        @DisplayName("counts all nested nodes")
        void countsAllNestedNodes() {
            var root = new FileNode(Path.of("/"), "/", true, 0);
            root.addChild(new FileNode(Path.of("/a.txt"), "a.txt", false, 10));
            root.addChild(new FileNode(Path.of("/b.txt"), "b.txt", false, 20));
            root.addChild(new FileNode(Path.of("/c.txt"), "c.txt", false, 30));

            assertEquals(4, TreemapLayout.countDescendants(root));
        }
    }

    @Nested
    @DisplayName("displayDepth")
    class DisplayDepth {

        @Test
        @DisplayName("leaf node has depth 0")
        void leafNodeDepth() {
            var file = new FileNode(Path.of("/a.txt"), "a.txt", false, 10);
            assertEquals(0, TreemapLayout.displayDepth(file));
        }

        @Test
        @DisplayName("directory with multiple children has depth 0")
        void dirWithMultipleChildrenDepth() {
            var dir = new FileNode(Path.of("/dir"), "dir", true, 0);
            dir.addChild(new FileNode(Path.of("/dir/a.txt"), "a.txt", false, 10));
            dir.addChild(new FileNode(Path.of("/dir/b.txt"), "b.txt", false, 10));
            assertEquals(0, TreemapLayout.displayDepth(dir));
        }

        @Test
        @DisplayName("chain of single-child directories increases depth")
        void chainOfSingleChildDirectories() {
            var outer = new FileNode(Path.of("/outer"), "outer", true, 0);
            var mid = new FileNode(Path.of("/outer/mid"), "mid", true, 0);
            var inner = new FileNode(Path.of("/outer/mid/inner"), "inner", true, 0);
            inner.addChild(new FileNode(Path.of("/outer/mid/inner/file.txt"), "file.txt", false, 10));
            mid.addChild(inner);
            outer.addChild(mid);

            assertEquals(2, TreemapLayout.displayDepth(outer));
            assertEquals(1, TreemapLayout.displayDepth(mid));
            assertEquals(0, TreemapLayout.displayDepth(inner));
        }

        @Test
        @DisplayName("depth capped at 4")
        void depthCappedAt4() {
            var node = new FileNode(Path.of("/l0"), "l0", true, 0);
            var current = node;
            for (int i = 1; i <= 5; i++) {
                var child = new FileNode(Path.of("/l" + i), "l" + i, true, 0);
                current.addChild(child);
                current = child;
            }
            current.addChild(new FileNode(Path.of("/file.txt"), "file.txt", false, 10));

            assertEquals(4, TreemapLayout.displayDepth(node));
        }
    }

    @Nested
    @DisplayName("squarify")
    class Squarify {

        @Test
        @DisplayName("distributes space equally for equal-sized children")
        void distributesEqually() {
            var children = List.of(
                    new FileNode(Path.of("/a.txt"), "a.txt", false, 50),
                    new FileNode(Path.of("/b.txt"), "b.txt", false, 50)
            );

            var map = TreemapLayout.squarify(children, 0, 0, 400, 200);

            assertEquals(2, map.size());

            var a = map.get(children.get(0));
            var b = map.get(children.get(1));

            double areaA = a.w() * a.h();
            double areaB = b.w() * b.h();
            assertEquals(areaA, areaB, areaA * 0.1);
        }

        @Test
        @DisplayName("handles zero total size by distributing space equally")
        void handlesZeroTotalSize() {
            var children = List.of(
                    new FileNode(Path.of("/a.txt"), "a.txt", false, 0),
                    new FileNode(Path.of("/b.txt"), "b.txt", false, 0)
            );

            var map = TreemapLayout.squarify(children, 0, 0, 400, 200);

            assertEquals(2, map.size());
            assertTrue(map.get(children.get(0)).w() > 0);
            assertTrue(map.get(children.get(1)).w() > 0);
        }

        @Test
        @DisplayName("3:1 size ratio produces roughly 3:1 area ratio")
        void proportionalDistribution() {
            var big = new FileNode(Path.of("/big.txt"), "big.txt", false, 75);
            var small = new FileNode(Path.of("/small.txt"), "small.txt", false, 25);
            var children = List.of(big, small);

            var map = TreemapLayout.squarify(children, 0, 0, 400, 200);

            double bigArea = map.get(big).w() * map.get(big).h();
            double smallArea = map.get(small).w() * map.get(small).h();

            double ratio = bigArea / smallArea;
            assertTrue(ratio >= 2.0 && ratio <= 4.0,
                    "expected ratio ~3.0, got " + ratio);
        }
    }

    private static boolean overlap(TreemapLayout.Rect a, TreemapLayout.Rect b) {
        if (a.x() + a.w() <= b.x() || b.x() + b.w() <= a.x()) return false;
        if (a.y() + a.h() <= b.y() || b.y() + b.h() <= a.y()) return false;
        return true;
    }
}
