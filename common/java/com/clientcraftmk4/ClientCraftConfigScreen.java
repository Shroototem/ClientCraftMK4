package com.clientcraftmk4;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class ClientCraftConfigScreen extends Screen {
    private final Screen parent;

    public ClientCraftConfigScreen(Screen parent) {
        super(Text.literal("ClientCraftMK4 Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2 - 100;
        int y = this.height / 2 - 50;

        addDrawableChild(CyclingButtonWidget.onOffBuilder()
                .initially(ClientCraftConfig.searchContainers)
                .build(centerX, y, 200, 20, Text.literal("Search In Containers"), (button, value) -> {
                    ClientCraftConfig.searchContainers = value;
                }));

        addDrawableChild(new DelaySlider(centerX, y + 26, 200, 20, ClientCraftConfig.delayTicks));

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            ClientCraftConfig.save();
            RecipeResolver.clearCache();
            client.setScreen(parent);
        }).dimensions(centerX, y + 62, 200, 20).build());
    }

    private static class DelaySlider extends SliderWidget {
        DelaySlider(int x, int y, int width, int height, int initial) {
            super(x, y, width, height, Text.literal("Delay In Ticks: " + initial), initial / 20.0);
        }

        @Override
        protected void updateMessage() {
            int ticks = (int) Math.round(this.value * 20);
            setMessage(Text.literal("Delay In Ticks: " + ticks));
        }

        @Override
        protected void applyValue() {
            ClientCraftConfig.delayTicks = (int) Math.round(this.value * 20);
        }
    }
}
