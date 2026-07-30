package by.snql.filescanner.ui;

import javafx.scene.paint.Color;

public enum FileTypeCategory {
    IMAGE(Color.rgb(0xE7, 0x4C, 0x3C), "jpg", "jpeg", "png", "gif", "bmp", "svg", "webp", "ico", "tiff", "tif", "psd", "raw", "heic"),
    VIDEO(Color.rgb(0xF3, 0x9C, 0x12), "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg"),
    AUDIO(Color.rgb(0x9B, 0x59, 0xB6), "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus", "aiff", "alac"),
    DOCUMENT(Color.rgb(0x34, 0x98, 0xDB), "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "md", "csv", "log"),
    ARCHIVE(Color.rgb(0xE6, 0x7E, 0x22), "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "rpm", "deb", "apk", "jar", "war", "ear", "iso", "dmg"),
    CODE(Color.rgb(0x2E, 0xCC, 0x71), "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php", "swift", "kt", "scala", "lua", "sh", "bash", "zsh", "sql", "html", "css", "xml", "json", "yaml", "yml", "toml", "cfg", "ini", "gradle", "properties"),
    EXECUTABLE(Color.rgb(0x1A, 0xBC, 0x9C), "exe", "dll", "so", "dylib", "bin", "app", "msi", "run"),
    FONT(Color.rgb(0xC0, 0x39, 0x2B), "ttf", "otf", "woff", "woff2", "eot"),
    DISK_IMAGE(Color.rgb(0x8E, 0x44, 0xAD), "vmdk", "vdi", "vhd", "vhdx", "qcow2", "img"),
    OTHER(Color.rgb(0x7F, 0x8C, 0x8D));

    private final Color color;
    private final String[] extensions;

    FileTypeCategory(Color color, String... extensions) {
        this.color = color;
        this.extensions = extensions;
    }

    public Color color() { return color; }

    public static FileTypeCategory forFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase();
            for (var cat : values()) {
                for (var e : cat.extensions) {
                    if (e.equals(ext)) return cat;
                }
            }
        }
        return OTHER;
    }
}
