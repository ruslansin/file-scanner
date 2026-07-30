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

    public FileNode(Path path, String name, boolean directory, long size) {
        this.path = path;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.children = new ArrayList<>();
    }

    public void addChild(FileNode child) {
        children.add(child);
        size += child.size;
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
}
