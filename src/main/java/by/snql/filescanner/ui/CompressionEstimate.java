package by.snql.filescanner.ui;

import by.snql.filescanner.model.FileNode;

import java.util.List;

public final class CompressionEstimate {

    private CompressionEstimate() {}

    public record CompressionResult(long originalSize, long estimatedCompressed, String strategy) {
        public long savings() { return originalSize - estimatedCompressed; }
        public double ratio() { return originalSize > 0 ? (double) estimatedCompressed / originalSize : 1.0; }
    }

    public static CompressionResult estimate(FileNode root) {
        var files = FileAnalysis.flattenFiles(root);
        long totalSize = files.stream().mapToLong(FileNode::getSize).sum();
        long compressed = 0;

        for (var file : files) {
            compressed += estimateCompressedSize(file);
        }

        return new CompressionResult(totalSize, compressed, "per-type heuristic");
    }

    public static long estimateCompressedSize(FileNode file) {
        if (file.getSize() == 0) return 0;
        String ext = ext(file.getName());
        double ratio = switch (ext) {
            case "jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "mp4", "mkv", "avi", "mov", "webm" -> 0.98;
            case "mp3", "wav", "flac", "aac", "ogg", "m4a" -> 0.95;
            case "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "jar", "war", "iso", "dmg", "apk", "rpm", "deb" -> 0.99;
            case "exe", "dll", "so", "dylib", "bin" -> 0.85;
            case "txt", "log", "csv", "json", "xml", "html", "css", "js" -> 0.15;
            case "sql", "md", "yaml", "yml" -> 0.20;
            case "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods" -> 0.30;
            default -> 0.50;
        };
        return (long) (file.getSize() * ratio);
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
