package by.snql.filescanner.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SizeFormat")
class SizeFormatTest {

    @Nested
    @DisplayName("zero and negative")
    class ZeroAndNegative {

        @Test
        @DisplayName("returns 0 B for zero")
        void returnsZeroBForZero() {
            assertEquals("0 B", SizeFormat.format(0));
        }

        @Test
        @DisplayName("returns 0 B for negative")
        void returnsZeroBForNegative() {
            assertEquals("0 B", SizeFormat.format(-1));
            assertEquals("0 B", SizeFormat.format(-1000));
        }
    }

    @Nested
    @DisplayName("bytes")
    class Bytes {

        @Test
        @DisplayName("formats single bytes")
        void formatsSingleBytes() {
            assertEquals("1.0 B", SizeFormat.format(1));
            assertEquals("512.0 B", SizeFormat.format(512));
            assertEquals("1023.0 B", SizeFormat.format(1023));
        }
    }

    @Nested
    @DisplayName("kilobytes")
    class Kilobytes {

        @Test
        @DisplayName("formats KB range")
        void formatsKbRange() {
            assertTrue(SizeFormat.format(1024).endsWith("KB"));
            assertTrue(SizeFormat.format(1024 * 512).endsWith("KB"));
        }
    }

    @Nested
    @DisplayName("megabytes")
    class Megabytes {

        @Test
        @DisplayName("formats MB range")
        void formatsMbRange() {
            assertTrue(SizeFormat.format(1024 * 1024).endsWith("MB"));
        }
    }

    @Nested
    @DisplayName("gigabytes")
    class Gigabytes {

        @Test
        @DisplayName("formats GB range")
        void formatsGbRange() {
            assertTrue(SizeFormat.format(1024L * 1024 * 1024).endsWith("GB"));
        }
    }

    @Nested
    @DisplayName("terabytes")
    class Terabytes {

        @Test
        @DisplayName("formats TB range")
        void formatsTbRange() {
            assertTrue(SizeFormat.format(1024L * 1024 * 1024 * 1024).endsWith("TB"));
        }
    }

    @Nested
    @DisplayName("locale independence")
    class LocaleIndependence {

        @Test
        @DisplayName("always uses a dot decimal separator regardless of default locale")
        void alwaysUsesDotSeparator() {
            var original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY); // uses comma as decimal separator
                assertEquals("1.5 KB", SizeFormat.format(1536));
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    @Nested
    @DisplayName("monotonic")
    class Monotonic {

        @Test
        @DisplayName("formatting never throws and never returns blank for positive sizes")
        void neverBlank() {
            long[] sizes = {1, 10, 100, 1023, 1024, 2048, 10_000, 100_000,
                    1024L * 1024, 1024L * 1024 * 10, 1024L * 1024 * 1024,
                    1024L * 1024 * 1024 * 100, 1024L * 1024 * 1024 * 1024};

            for (long size : sizes) {
                String formatted = SizeFormat.format(size);
                assertNotNull(formatted);
                assertFalse(formatted.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @DisplayName("formats known values correctly")
    @CsvSource({
            "0,        0 B",
            "1,        1.0 B",
            "1024,     1.0 KB",
            "1536,     1.5 KB",
            "1048576,  1.0 MB",
            "1073741824, 1.0 GB",
    })
    void formatsKnownValues(long bytes, String expected) {
        assertEquals(expected, SizeFormat.format(bytes));
    }
}
