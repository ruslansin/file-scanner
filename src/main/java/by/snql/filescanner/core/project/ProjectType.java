package by.snql.filescanner.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public enum ProjectType {

    MAVEN("Maven", "pom.xml",
            List.of("target")),
    GRADLE("Gradle", "build.gradle",
            List.of("build", ".gradle")),
    GRADLE_KTS("Gradle (Kotlin)", "build.gradle.kts",
            List.of("build", ".gradle")),
    GRADLE_SETTINGS("Gradle", "settings.gradle",
            List.of("build", ".gradle")),
    GRADLE_SETTINGS_KTS("Gradle (Kotlin)", "settings.gradle.kts",
            List.of("build", ".gradle")),
    NODE("Node.js", "package.json",
            List.of("node_modules", "dist", ".next", ".nuxt", ".output")),
    PYTHON("Python", "pyproject.toml",
            List.of("__pycache__", "venv", ".venv", "build", "dist", ".pytest_cache")),
    PYTHON_SETUP("Python", "setup.py",
            List.of("__pycache__", "venv", ".venv", "build", "dist")),
    PYTHON_SETUP_CFG("Python", "setup.cfg",
            List.of("__pycache__", "venv", ".venv", "build", "dist")),
    RUST("Rust", "Cargo.toml",
            List.of("target")),
    GO("Go", "go.mod",
            List.of("vendor")),
    DOTNET(".NET", null,
            List.of("bin", "obj")) {
        @Override
        boolean matches(Path dir) {
            try (var s = Files.list(dir)) {
                return s.anyMatch(p -> matchesDotnetFile(p.getFileName().toString()));
            } catch (Exception e) { return false; }
        }

        @Override
        boolean matches(Set<String> namesInDir) {
            return namesInDir.stream().anyMatch(ProjectType::matchesDotnetFile);
        }
    },
    PHP("PHP", "composer.json",
            List.of("vendor")),
    CMAKE("CMake", "CMakeLists.txt",
            List.of("build")),
    MAKEFILE("Make", "Makefile",
            List.of("build"));

    private final String displayName;
    private final String configFile;
    private final List<String> artifacts;

    ProjectType(String displayName, String configFile, List<String> artifacts) {
        this.displayName = displayName;
        this.configFile = configFile;
        this.artifacts = artifacts;
    }

    public String displayName() { return displayName; }
    public List<String> artifacts() { return artifacts; }

    boolean matches(Path dir) {
        if (configFile == null) return false;
        return Files.exists(dir.resolve(configFile));
    }

    /** Faster variant used when the directory listing is already available (avoids a stat per candidate type). */
    boolean matches(Set<String> namesInDir) {
        return configFile != null && namesInDir.contains(configFile);
    }

    private static boolean matchesDotnetFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".csproj") || n.endsWith(".sln") || n.endsWith(".vbproj");
    }

    /**
     * Detects the project type of a single directory by its own marker file.
     * Does not look at ancestors. One {@code Files.exists} (or listing) call per
     * candidate type — prefer {@link #detect(Set)} when you already have the
     * directory's file listing (e.g. while recursively walking a tree).
     */
    public static Optional<ProjectType> detect(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return Optional.empty();
        for (var type : values()) {
            if (type.matches(dir)) return Optional.of(type);
        }
        return Optional.empty();
    }

    /** Detects the project type from an already-obtained set of file/dir names within a directory. */
    public static Optional<ProjectType> detect(Set<String> namesInDir) {
        for (var type : values()) {
            if (type.matches(namesInDir)) return Optional.of(type);
        }
        return Optional.empty();
    }

    public static boolean isArtifactName(String name) {
        for (var type : values()) {
            if (type.artifacts.contains(name)) return true;
        }
        return false;
    }
}
