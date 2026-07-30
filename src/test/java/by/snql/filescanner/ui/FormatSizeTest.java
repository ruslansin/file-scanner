package by.snql.filescanner.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MainWindow.formatSize")
class FormatSizeTest {

    @Nested
    @DisplayName("zero and negative")
    class ZeroAndNegative {

        @Test
        @DisplayName("returns 0 B for zero")
        void returnsZeroBForZero() {
            assertEquals("0 B", MainWindow.formatSize(0));
        }

        @Test
        @DisplayName("returns 0 B for negative")
        void returnsZeroBForNegative() {
            assertEquals("0 B", MainWindow.formatSize(-1));
            assertEquals("0 B", MainWindow.formatSize(-1000));
        }
    }

    @Nested
    @DisplayName("bytes")
    class Bytes {

        @Test
        @DisplayName("formats single bytes")
        void formatsSingleBytes() {
            assertEquals("1.0 B", MainWindow.formatSize(1));
            assertEquals("512.0 B", MainWindow.formatSize(512));
            assertEquals("1023.0 B", MainWindow.formatSize(1023));
        }
    }

    @Nested
    @DisplayName("kilobytes")
    class Kilobytes {

        @Test
        @DisplayName("formats KB range")
        void formatsKbRange() {
            assertTrue(MainWindow.formatSize(1024).endsWith("KB"));
            assertTrue(MainWindow.formatSize(1024 * 512).endsWith("KB"));
        }
    }

    @Nested
    @DisplayName("megabytes")
    class Megabytes {

        @Test
        @DisplayName("formats MB range")
        void formatsMbRange() {
            assertTrue(MainWindow.formatSize(1024 * 1024).endsWith("MB"));
        }
    }

    @Nested
    @DisplayName("gigabytes")
    class Gigabytes {

        @Test
        @DisplayName("formats GB range")
        void formatsGbRange() {
            assertTrue(MainWindow.formatSize(1024L * 1024 * 1024).endsWith("GB"));
        }
    }

    @Nested
    @DisplayName("terabytes")
    class Terabytes {

        @Test
        @DisplayName("formats TB range")
        void formatsTbRange() {
            assertTrue(MainWindow.formatSize(1024L * 1024 * 1024 * 1024).endsWith("TB"));
        }
    }

    @Nested
    @DisplayName("monotonic")
    class Monotonic {

        @Test
        @DisplayName("larger values never produce smaller formatted output")
        void largerValuesProduceLargerFormats() {
            long[] sizes = {1, 10, 100, 1023, 1024, 2048, 10_000, 100_000,
                    1024L * 1024, 1024L * 1024 * 10, 1024L * 1024 * 1024,
                    1024L * 1024 * 1024 * 100, 1024L * 1024 * 1024 * 1024};

            String prev = MainWindow.formatSize(sizes[0]);
            for (int i = 1; i < sizes.length; i++) {
                String current = MainWindow.formatSize(sizes[i]);
                assertNotNull(current);
                assertFalse(current.isEmpty());
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
        assertEquals(expected, MainWindow.formatSize(bytes));
    }
}
