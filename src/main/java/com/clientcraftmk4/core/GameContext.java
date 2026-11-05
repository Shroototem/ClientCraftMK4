package com.clientcraftmk4.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractCraftingMenu;

/**
 * Minimal game-state accessors. Version-pinned via Stonecutter comments:
 * 26.2 exposes the open screen through {@code minecraft.gui.screen()}, earlier
 * versions through {@code minecraft.screen}.
 */
public final class GameContext {
    private static long clientTicks;

    private GameContext() {}

    /** Advances the tick counter; registered on END_CLIENT_TICK. */
    public static void onClientTick() {
        clientTicks++;
    }

    /** Client ticks since startup: lets per-frame code do its work at most once per tick. */
    public static long clientTick() {
        return clientTicks;
    }

    /** The crafting menu of the open crafting table or inventory screen, or null. */
    public static AbstractCraftingMenu craftingMenu() {
        Screen screen = currentScreen();
        if (screen instanceof CraftingScreen s) return s.getMenu();
        if (screen instanceof InventoryScreen s) return s.getMenu();
        return null;
    }

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
