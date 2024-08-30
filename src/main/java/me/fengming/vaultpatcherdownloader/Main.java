package me.fengming.vaultpatcherdownloader;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import me.fengming.vaultpatcher_asm.VaultPatcher;
import me.fengming.vaultpatcher_asm.config.VaultPatcherConfig;
import me.fengming.vaultpatcher_asm.core.utils.Utils;
import me.fengming.vaultpatcher_asm.plugin.VaultPatcherPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Main implements VaultPatcherPlugin {
    public static Path configFile;
    public static HashMap<String, String> configMap;
    public static Gson GSON = new Gson();
    public static Logger LOGGER = LogManager.getLogger("VPDL");

    private List<String> addMod = new ArrayList<>();

    @Override
    public String getName() {
        return "vpdl";
    }

    @Override
    public void start(Path path) {
        LOGGER.info("[VPDL] Loading VPDL");
        configFile = Utils.getPluginConfigPath(this).resolve("config.json");
        if (Files.notExists(configFile)) {
            try {
                Files.createDirectories(configFile.getParent());
                Files.createFile(configFile);
                JsonObject obj = new JsonObject();
                obj.add("download_source", new JsonPrimitive("auto"));
                obj.add("index", new JsonPrimitive(""));
                String defaultConfig = new GsonBuilder().setPrettyPrinting().create().toJson(obj);
                BufferedWriter bw = Files.newBufferedWriter(configFile);
                bw.write(defaultConfig);
                bw.flush();
                bw.close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to create config file: " + e);
            }
        }

        try {
            configMap = GSON.fromJson(new FileReader(configFile.toFile()), new TypeToken<HashMap<String, String>>(){}.getType());
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Failed to read config file: " + e);
        }

//        HashSet<String> modIds = new HashSet<>();
//        Arrays.stream(path.resolve("mods").toFile().listFiles()).map(this::getModId).forEach(modIds::add);

        HashSet<String> modIds = new HashSet<>();
        File modsDir = path.resolve("mods").toFile();

        File[] files = modsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    modIds.add(getModId(file));
                }
            }
        }

        String downloadSource = configMap.get("download_source");
        String indexUrl;

        if (downloadSource.equalsIgnoreCase("auto")) {
            indexUrl = "https://vpdl.nvoid.me/index.json";
        } else if (downloadSource.equalsIgnoreCase("config")) {
            indexUrl = configMap.get("index");
        } else {
            throw new IllegalArgumentException("Unknown download source: " + downloadSource);
        }
        try {
            String indexJson = Downloader.getString(new URL(indexUrl));
            Index index = GSON.fromJson(indexJson, new TypeToken<Index>() {}.getType());
            List<Index.Mod> mods = new ArrayList<>();
            index.mods.stream().filter(mod -> modIds.contains(mod.name) && matchVersion(mod.version)).forEach(mods::add);
            for (Index.Mod mod : mods) {
                Path patchFile = path.resolve("config").resolve("vaultpatcher_asm").resolve(mod.name + ".json");
                try (BufferedWriter bw = Files.newBufferedWriter(patchFile, StandardCharsets.UTF_8)) {
                    String patch = Downloader.getString(new URL(mod.url.replace("{root}", index.root)));
                    if (Files.notExists(patchFile)) {
                        Files.createDirectories(patchFile.getParent());
                        Files.createFile(patchFile);
                    }
                    bw.write(patch);
                    bw.flush();
                } catch (IOException e) {
                    throw new RuntimeException("Failed write the patch: " + e);
                }
                addMod.add(mod.name);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Unknown URL: " + e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed downloading index: " + e);
        }
    }

    @Override
    public void onLoadConfig(Phase phase) {
        if (phase == Phase.AFTER) {
            addMod.stream().filter(e -> !VaultPatcherConfig.mods.contains(e)).forEach(VaultPatcherConfig.mods::add);
            // VaultPatcherConfig.mods.addAll(addMod);
            try {
                VaultPatcherConfig.save();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void end() {
        LOGGER.info("[VPDL] Goodbye!");
    }

    private String getModId(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry fabricJson = jar.getJarEntry("fabric.mod.json");
            boolean isFabric = fabricJson != null;
            String modId = null;
            if (isFabric) {
                JsonReader jr = GSON.newJsonReader(new InputStreamReader(jar.getInputStream(fabricJson)));
                jr.beginObject();
                while (jr.peek() != JsonToken.END_OBJECT) {
                    if (jr.peek() == JsonToken.NAME && jr.nextName().equals("id")) {
                        modId = jr.nextString();
                    } else {
                        jr.skipValue();
                    }
                }
            } else {
                JarEntry forgeToml = jar.getJarEntry("META-INF/mods.toml");
                if (forgeToml == null) {
                    return null;
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(jar.getInputStream(forgeToml)));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("modId=\"")) {
                        modId = line.substring(7, line.lastIndexOf('\"'));
                        break;
                    }
                }
            }
            return modId;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed loading mod: " + jarFile.toString(), e);
        }
    }

    private boolean matchVersion(String range) {
        String version = Utils.getGameVersion();
        if (version == null) return false;
        final Comparator<String> versionComparator = (v1, v2) -> {
            String[] v1Parts = v1.split("\\.");
            String[] v2Parts = v2.split("\\.");
            int length = Math.max(v1Parts.length, v2Parts.length);
            for (int i = 0; i < length; i++) {
                int v1Part = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
                int v2Part = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;
                if (v1Part < v2Part)
                    return -1;
                if (v1Part > v2Part)
                    return 1;
            }
            return 0;
        };
        if (range.endsWith("+")) {
            range = range.substring(0, range.length() - 1) + "-99.99.99";
        }
        String[] rangeParts = range.split("-");
        if (rangeParts.length < 2) {
            return range.equals(version);
        }
        String lowerBound = rangeParts[0];
        String upperBound = rangeParts[1];
        return versionComparator.compare(lowerBound, upperBound) <= 0 ?
                versionComparator.compare(version, lowerBound) >= 0 && versionComparator.compare(version, upperBound) <= 0 :
                versionComparator.compare(version, upperBound) >= 0 && versionComparator.compare(version, lowerBound) <= 0;
    }
}