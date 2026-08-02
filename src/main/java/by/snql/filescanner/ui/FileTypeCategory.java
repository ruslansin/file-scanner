package by.snql.filescanner.ui;

import by.snql.filescanner.core.analysis.FileCategory;
import javafx.scene.paint.Color;

import java.util.Map;

/** Maps the platform-independent {@link FileCategory} to JavaFX colors for chart rendering. */
public final class FileTypeCategory {

    private FileTypeCategory() {}

    private static final Map<FileCategory, Color> COLORS = Map.of(
            FileCategory.IMAGE, Color.rgb(0xE7, 0x4C, 0x3C),
            FileCategory.VIDEO, Color.rgb(0xF3, 0x9C, 0x12),
            FileCategory.AUDIO, Color.rgb(0x9B, 0x59, 0xB6),
            FileCategory.DOCUMENT, Color.rgb(0x34, 0x98, 0xDB),
            FileCategory.ARCHIVE, Color.rgb(0xE6, 0x7E, 0x22),
            FileCategory.CODE, Color.rgb(0x2E, 0xCC, 0x71),
            FileCategory.EXECUTABLE, Color.rgb(0x1A, 0xBC, 0x9C),
            FileCategory.FONT, Color.rgb(0xC0, 0x39, 0x2B),
            FileCategory.DISK_IMAGE, Color.rgb(0x8E, 0x44, 0xAD),
            FileCategory.OTHER, Color.rgb(0x7F, 0x8C, 0x8D)
    );

    public static Color colorFor(String fileName) {
        return COLORS.get(FileCategory.forFile(fileName));
    }
}
