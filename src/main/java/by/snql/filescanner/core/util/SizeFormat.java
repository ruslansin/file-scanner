package by.snql.filescanner.core.util;

import java.util.Locale;

/**
 * Formats byte counts as human-readable strings (e.g. "1.5 MB").
 * Locale-independent — always uses a dot as decimal separator regardless
 * of the JVM default locale.
 */
public final class SizeFormat {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private SizeFormat() {}

    public static String format(long bytes) {
        if (bytes <= 0) return "0 B";
        int unit = (int) (Math.log10(bytes) / Math.log10(1024));
        unit = Math.min(unit, UNITS.length - 1);
        double value = bytes / Math.pow(1024, unit);
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
