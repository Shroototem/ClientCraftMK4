package com.clientcraftmk4.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON config with identical field semantics to MK4 (plan §5.6 / §16).
 */
public class ClientCraftConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("clientcraftmk4.json");

    public static int delayTicks = 0;
    public static boolean searchContainers = false;
    public static boolean quickCountMode = false;
    public static boolean debugLogging = false;

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            Data data = read(CONFIG_PATH);
            if (data != null) apply(data);
        }
    }

    private static Data read(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, Data.class);
        } catch (IOException | JsonSyntaxException e) {
            // Malformed JSON previously escaped and killed onInitializeClient; fall back
            // to defaults instead (normal-path behavior unchanged).
            return null;
        }
    }

    private static void apply(Data data) {
        delayTicks = Math.clamp(data.delayTicks, 0, 20);
        searchContainers = data.searchContainers;
        quickCountMode = data.quickCountMode;
        debugLogging = data.debugLogging;
    }

    public static void save() {
        Data data = new Data();
        data.delayTicks = delayTicks;
        data.searchContainers = searchContainers;
        data.quickCountMode = quickCountMode;
        data.debugLogging = debugLogging;
        // Atomic write: a crash mid-save previously left a truncated
        // clientcraftmk4.json behind. Same file content on success.
        Path tmp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            GSON.toJson(data, writer);
        } catch (IOException ignored) {
            return;
        }
        try {
            Files.move(tmp, CONFIG_PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            try {
                Files.move(tmp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }
    }

    private static class Data {
        int schemaVersion = 1;
        int delayTicks = 0;
        boolean searchContainers = false;
        boolean quickCountMode = false;
        boolean debugLogging = false;
    }
}
