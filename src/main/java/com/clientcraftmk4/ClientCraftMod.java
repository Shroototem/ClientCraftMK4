package com.clientcraftmk4;

import com.clientcraftmk4.config.ClientCraftConfig;
import com.clientcraftmk4.craft.AutoCrafter;
import com.clientcraftmk4.pipeline.ResolveCache;
import com.clientcraftmk4.pipeline.ResolvePipeline;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ClientCraftMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCraftConfig.load();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ResolveCache.clearAll();
            ResolvePipeline.clearWarmup();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ResolvePipeline.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(client -> ResolvePipeline.warmupIfRequested());
        AutoCrafter.registerTickHandler();
    }
}
