package by.snql.filescanner.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FileNode implements Comparable<FileNode> {

    private final Path path;
    private final String name;
    private final boolean directory;
    private long size;
    private final List<FileNode> children;
    private boolean buildArtifact;
    private boolean symlink;
    private boolean hardlinkReference;
    private long lastModified;

    public FileNode(Path path, String name, boolean directory, long size) {
        this.path = path;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.children = new ArrayList<>();
    }

    /**
     * Adds a freshly-discovered child and accumulates its size into this node's size.
     * Use this while building a tree bottom-up during a scan.
     */
    public void addChild(FileNode child) {
        children.add(child);
        size += child.size;
    }

    /**
     * Attaches a child whose size (and its subtree's contribution) is already
     * reflected in this node's own {@link #size}. Does NOT accumulate size again.
     * Use this when reconstructing a tree from a source (cache, snapshot, deep copy)
     * that already stored the correct aggregate sizes — calling {@link #addChild}
     * here would double- (or n-times-) count every node's size by its depth.
     */
    public void attachChild(FileNode child) {
        children.add(child);
    }

    public Path getPath() { return path; }
    public String getName() { return name; }
    public boolean isDirectory() { return directory; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public List<FileNode> getChildren() { return children; }
    public boolean isLeaf() { return children.isEmpty(); }

    public boolean isBuildArtifact() { return buildArtifact; }
    public void setBuildArtifact(boolean b) { this.buildArtifact = b; }

    public boolean isSymlink() { return symlink; }
    public void setSymlink(boolean s) { this.symlink = s; }

    public boolean isHardlinkReference() { return hardlinkReference; }
    public void setHardlinkReference(boolean h) { this.hardlinkReference = h; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lm) { this.lastModified = lm; }

    public void sortChildren() {
        children.sort(FileNode::compareTo);
        for (FileNode child : children) {
            child.sortChildren();
        }
    }

    @Override
    public int compareTo(FileNode other) {
        return Long.compare(other.size, this.size);
    }

    /**
     * Deep-copies a tree, preserving each node's already-computed size (via
     * {@link #attachChild}, not {@link #addChild}). Safe to call on a tree that
     * another thread may still be mutating concurrently: reads its own local
     * variables of {@code node} only once per node before recursing, so at worst
     * it may miss very recent children — it will never observe a torn/inconsistent
     * children list from a concurrent structural modification exception.
     */
    public static FileNode copyOf(FileNode node) {
        var copy = new FileNode(node.path, node.name, node.directory, node.size);
        copy.buildArtifact = node.buildArtifact;
        copy.symlink = node.symlink;
        copy.hardlinkReference = node.hardlinkReference;
        copy.lastModified = node.lastModified;
        for (var child : List.copyOf(node.children)) {
            copy.attachChild(copyOf(child));
        }
        return copy;
    }
}
