package com.clientcraftmk4;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public class ClientCraftConfigScreen extends Screen {
    private final Screen parent;

    public ClientCraftConfigScreen(Screen parent) {
        super(Component.literal("ClientCraftMK4 Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2 - 100;
        int y = this.height / 2 - 50;

        addRenderableWidget(CycleButton.onOffBuilder(ClientCraftConfig.searchContainers)
                .create(centerX, y, 200, 20, Component.literal("Search In Containers"), (button, value) -> {
                    ClientCraftConfig.searchContainers = value;
                }));

        addRenderableWidget(CycleButton.onOffBuilder(ClientCraftConfig.quickCountMode)
                .create(centerX, y + 26, 200, 20, Component.literal("Quick Count Mode"), (button, value) -> {
                    ClientCraftConfig.quickCountMode = value;
                }));

        addRenderableWidget(new DelaySlider(centerX, y + 52, 200, 20, ClientCraftConfig.delayTicks));

        addRenderableWidget(CycleButton.onOffBuilder(ClientCraftConfig.debugLogging)
                .create(centerX, y + 78, 200, 20, Component.literal("Debug Logging"), (button, value) -> {
                    ClientCraftConfig.debugLogging = value;
                }));

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
            ClientCraftConfig.save();
            RecipeResolver.clearCache();
            minecraft.setScreen(parent);
        }).bounds(centerX, y + 114, 200, 20).build());
    }

    private static class DelaySlider extends AbstractSliderButton {
        DelaySlider(int x, int y, int width, int height, int initial) {
            super(x, y, width, height, Component.literal("Delay In Ticks: " + initial), initial / 20.0);
        }

        @Override
        protected void updateMessage() {
            int ticks = (int) Math.round(this.value * 20);
            setMessage(Component.literal("Delay In Ticks: " + ticks));
        }

        @Override
        protected void applyValue() {
            ClientCraftConfig.delayTicks = (int) Math.round(this.value * 20);
        }
    }
}
