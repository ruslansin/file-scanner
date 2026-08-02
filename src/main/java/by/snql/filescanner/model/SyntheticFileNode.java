package by.snql.filescanner.model;

import java.nio.file.Path;
import java.util.List;

public class SyntheticFileNode extends FileNode {

    public SyntheticFileNode(String name, List<FileNode> children) {
        super(Path.of(""), name, true,
                children.stream().mapToLong(FileNode::getSize).sum());
        children.forEach(this::attachChild);
    }
}
