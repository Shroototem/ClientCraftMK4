package com.clientcraftmk4;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class ClientCraftMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCraftConfig.load();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RecipeResolver.clearCache());
        AutoCrafter.registerTickHandler();
    }
}
