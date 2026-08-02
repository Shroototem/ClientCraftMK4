package com.clientcraftmk4.pipeline;

import net.minecraft.client.Minecraft;

/** Publishes work to the render thread (plan §4.3). */
public final class RenderSync {
    private RenderSync() {}

    public static void run(Runnable r) {
        Minecraft.getInstance().execute(r);
    }
}
