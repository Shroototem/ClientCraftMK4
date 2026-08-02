package com.clientcraftmk4;

import com.clientcraftmk4.config.ClientCraftConfig;
import com.clientcraftmk4.craft.AutoCrafter;
import com.clientcraftmk4.pipeline.ResolveCache;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ClientCraftMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCraftConfig.load();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ResolveCache.clearAll());
        AutoCrafter.registerTickHandler();
    }
}
