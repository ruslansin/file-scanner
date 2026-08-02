package by.snql.filescanner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Settings {

    private static final Logger LOG = Logger.getLogger(Settings.class.getName());

    private static final Path CONFIG_DIR = Path.of(
            System.getProperty("user.home"), ".filescanner");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean duplicateSHA256 = false;
    public boolean scanHidden = true;
    public boolean darkMode = false;
    public String defaultSort = "size";
    public List<String> scanRoots = new ArrayList<>();
    public boolean projectScanEnabled = true;
    public int projectScanDepth = 5;
    public String lastScannedPath = "";
    public boolean moveToTrash = true;
    public List<String> recentPaths = new ArrayList<>();

    private static Settings instance;

    public static synchronized Settings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            var tmp = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            Files.move(tmp, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save settings", e);
        }
    }

    private static Settings load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                String json = Files.readString(CONFIG_FILE);
                var s = GSON.fromJson(json, Settings.class);
                if (s != null) return sanitize(s);
            } catch (IOException | JsonSyntaxException e) {
                LOG.log(Level.WARNING, "Corrupt settings.json, resetting to defaults", e);
            }
        }
        var defaults = new Settings();
        defaults.save();
        return defaults;
    }

    private static Settings sanitize(Settings s) {
        if (s.scanRoots == null) s.scanRoots = new ArrayList<>();
        if (s.recentPaths == null) s.recentPaths = new ArrayList<>();
        if (s.defaultSort == null) s.defaultSort = "size";
        if (s.lastScannedPath == null) s.lastScannedPath = "";
        return s;
    }
}
