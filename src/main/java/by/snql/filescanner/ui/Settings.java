package by.snql.filescanner.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Settings {

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

    private static Settings instance;

    public static Settings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(CONFIG_FILE, GSON.toJson(this));
        } catch (IOException e) {
        }
    }

    private static Settings load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                String json = Files.readString(CONFIG_FILE);
                var s = GSON.fromJson(json, Settings.class);
                if (s != null) return s;
            } catch (IOException e) {
            }
        }
        var defaults = new Settings();
        defaults.save();
        return defaults;
    }
}
