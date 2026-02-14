package com.clientcraftmk4;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClientCraftConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("clientcraftmk4.json");

    public static int delayTicks = 0;
    public static boolean searchContainers = false;

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                Data data = GSON.fromJson(reader, Data.class);
                if (data != null) {
                    delayTicks = Math.clamp(data.delayTicks, 0, 20);
                    searchContainers = data.searchContainers;
                }
            } catch (IOException ignored) {}
        }
    }

    public static void save() {
        Data data = new Data();
        data.delayTicks = delayTicks;
        data.searchContainers = searchContainers;
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(data, writer);
        } catch (IOException ignored) {}
    }

    private static class Data {
        int delayTicks = 0;
        boolean searchContainers = false;
    }
}
