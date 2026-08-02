package by.snql.filescanner.core.analysis;

import java.util.HashMap;
import java.util.Map;

/**
 * File type category based on file extension. Pure data — no UI dependency.
 * Used by compression estimates, grouping, exports, and (via a colour map)
 * by the chart views.
 */
public enum FileCategory {
    IMAGE("jpg", "jpeg", "png", "gif", "bmp", "svg", "webp", "ico", "tiff", "tif", "psd", "raw", "heic"),
    VIDEO("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg"),
    AUDIO("mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus", "aiff", "alac"),
    DOCUMENT("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "md", "csv", "log"),
    ARCHIVE("zip", "tar", "gz", "bz2", "xz", "7z", "rar", "rpm", "deb", "apk", "jar", "war", "ear", "iso", "dmg"),
    CODE("java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php",
            "swift", "kt", "scala", "lua", "sh", "bash", "zsh", "sql", "html", "css", "xml", "json",
            "yaml", "yml", "toml", "cfg", "ini", "gradle", "properties"),
    EXECUTABLE("exe", "dll", "so", "dylib", "bin", "app", "msi", "run"),
    FONT("ttf", "otf", "woff", "woff2", "eot"),
    DISK_IMAGE("vmdk", "vdi", "vhd", "vhdx", "qcow2", "img"),
    OTHER();

    private final String[] extensions;

    FileCategory(String... extensions) {
        this.extensions = extensions;
    }

    private static final Map<String, FileCategory> BY_EXTENSION = new HashMap<>();
    static {
        for (var cat : values()) {
            for (var ext : cat.extensions) {
                BY_EXTENSION.put(ext, cat);
            }
        }
    }

    public static FileCategory forFile(String name) {
        return BY_EXTENSION.getOrDefault(extension(name), OTHER);
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 && dot < name.length() - 1) ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }
}
