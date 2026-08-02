package by.snql.filescanner.core.cleanup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SystemCleanup")
class SystemCleanupTest {

    @Nested
    @DisplayName("calculateSize")
    class CalculateSize {

        @Test
        @DisplayName("returns 0 for non-existent path")
        void returnsZeroForNonexistent() {
            var target = new SystemCleanup.Target("test", "/nonexistent/path", "desc");
            assertEquals(0, SystemCleanup.calculateSize(target));
        }

        @Test
        @DisplayName("calculates directory size")
        void calculatesDirectorySize(@TempDir Path tmp) throws IOException {
            Files.writeString(tmp.resolve("a.txt"), "hello");
            Files.writeString(tmp.resolve("b.txt"), "world!");

            var target = new SystemCleanup.Target("test", tmp.toString(), "desc");
            long size = SystemCleanup.calculateSize(target);
            assertTrue(size >= 10);
        }

        @Test
        @DisplayName("handles single file")
        void handlesSingleFile(@TempDir Path tmp) throws IOException {
            var file = tmp.resolve("data.txt");
            Files.writeString(file, "A".repeat(42));

            var target = new SystemCleanup.Target("test", file.toString(), "desc");
            assertEquals(42, SystemCleanup.calculateSize(target));
        }
    }

    @Nested
    @DisplayName("resolvePath")
    class ResolvePath {

        @Test
        @DisplayName("resolves $HOME")
        void resolvesHome() {
            var target = new SystemCleanup.Target("test", "$HOME", "desc");
            var resolved = SystemCleanup.resolvePath(target);
            assertNotNull(resolved);
            assertTrue(Files.exists(resolved));
        }

        @Test
        @DisplayName("returns null for non-existent path")
        void returnsNullForNonexistent() {
            var target = new SystemCleanup.Target("test", "/nonexistent/path/xyz", "desc");
            assertNull(SystemCleanup.resolvePath(target));
        }
    }

    @Nested
    @DisplayName("targets")
    class Targets {

        @Test
        @DisplayName("returns OS-specific targets")
        void returnsOsSpecificTargets() {
            var targets = SystemCleanup.targets();
            assertNotNull(targets);
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                assertTrue(targets.stream().anyMatch(t -> t.name().contains("Windows Update") || t.name().contains("Temp")));
            } else if (os.contains("mac")) {
                assertTrue(targets.stream().anyMatch(t -> t.name().contains("Cache") || t.name().contains("Logs")));
            } else {
                assertTrue(targets.stream().anyMatch(t -> t.name().contains("APT") || t.name().contains("Journal") || t.name().contains("Cache") || t.name().contains("tmp")));
            }
        }

        @Test
        @DisplayName("includes developer tool targets")
        void includesDeveloperTargets() {
            var targets = SystemCleanup.targets();
            var devTargets = targets.stream()
                    .filter(t -> t.name().contains("Maven") || t.name().contains("Gradle") ||
                            t.name().contains("npm") || t.name().contains("pip") ||
                            t.name().contains("Docker"))
                    .toList();
            assertFalse(devTargets.isEmpty(), "Expected at least one developer tool target");
        }

        @Test
        @DisplayName("all resolved targets exist on disk")
        void allResolvedTargetsExist() {
            for (var t : SystemCleanup.targets()) {
                var resolved = SystemCleanup.resolvePath(t);
                if (resolved != null) {
                    assertTrue(Files.exists(resolved),
                            t.name() + " resolved to " + resolved + " which does not exist");
                }
            }
        }
    }

    @Nested
    @DisplayName("calculateSizesViaElevation")
    class CalculateSizesViaElevation {

        @Test
        @DisplayName("returns empty map for empty list without prompting for elevation")
        void returnsEmptyForEmptyList() {
            // Deliberately does NOT test the non-empty path: that triggers a real OS
            // elevation prompt (UAC / pkexec / osascript), which must never happen as
            // a side effect of running the unit test suite.
            var result = SystemCleanup.calculateSizesViaElevation(List.of());
            assertTrue(result.isEmpty());
        }
    }
}
