package by.snql.filescanner.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
                return s.anyMatch(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".csproj") || n.endsWith(".sln") || n.endsWith(".vbproj");
                });
            } catch (Exception e) { return false; }
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

    public static Optional<ProjectType> detect(Path dir) {
        for (var type : values()) {
            if (type.matches(dir)) return Optional.of(type);
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
