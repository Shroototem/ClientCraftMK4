package com.clientcraftmk4.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;

/**
 * Minimal game-state accessors. Version-pinned via Stonecutter comments:
 * 26.2 exposes the open screen through {@code minecraft.gui.screen()}, earlier
 * versions through {@code minecraft.screen}.
 */
public final class GameContext {
    private GameContext() {}

    /** The currently open screen. */
    public static Screen currentScreen() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.screen();
        //?}
        //? if <26.2 {
        /*return Minecraft.getInstance().screen;*/
        //?}
    }

    /** Grid size of the currently open crafting UI: 3 in a crafting table, 2 otherwise. */
    public static int gridSize() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.screen() instanceof CraftingScreen ? 3 : 2;
        //?}
        //? if <26.2 {
        /*return Minecraft.getInstance().screen instanceof CraftingScreen ? 3 : 2;*/
        //?}
    }
}
