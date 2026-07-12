package com.clientcraftmk4.benchmark;

import com.clientcraftmk4.RecipeResolver;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers the client-side {@code /ccdump} command, which captures the live crafting
 * recipe collections plus referenced tag membership into {@code <gameDir>/benchmark-recipes.dat}.
 * That file is the input replayed headlessly by the benchmark ({@code ./gradlew benchmark}).
 *
 * <p>This is inert unless the command is run, so it never affects normal mod behaviour.
 */
public final class BenchmarkSnapshotDumper {

    public static final String SNAPSHOT_FILE = "benchmark-recipes.dat";

    private BenchmarkSnapshotDumper() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("ccdump").executes(ctx -> {
                    dump(ctx.getSource()::sendFeedback);
                    return Command.SINGLE_SUCCESS;
                })));
    }

    private static void dump(java.util.function.Consumer<Component> feedback) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            feedback.accept(Component.literal("[ClientCraft] Join a world before dumping the benchmark snapshot."));
            return;
        }

        List<RecipeCollection> collections = RecipeResolver.getEnvironment().craftingCollections();

        List<List<RecipeDisplayEntry>> collectionLists = new ArrayList<>(collections.size());
        for (RecipeCollection coll : collections) {
            collectionLists.add(new ArrayList<>(coll.getRecipes()));
        }

        Set<TagKey<Item>> referenced = RecipeResolver.collectReferencedTags(collections);
        Map<TagKey<Item>, List<Item>> tagMembers = new HashMap<>(referenced.size() * 2);
        for (TagKey<Item> tag : referenced) {
            List<Item> members = RecipeResolver.getOrComputeTagMembers(tag);
            tagMembers.put(tag, members == null ? List.of() : members);
        }

        BenchmarkSnapshot snapshot = new BenchmarkSnapshot(collectionLists, tagMembers);
        Path out = client.gameDirectory.toPath().resolve(SNAPSHOT_FILE);
        try {
            snapshot.write(out, client.level.registryAccess());
            feedback.accept(Component.literal(String.format(
                    "[ClientCraft] Wrote %d recipes across %d collections and %d tags to %s",
                    snapshot.recipeCount(), collectionLists.size(), tagMembers.size(), out)));
        } catch (Exception e) {
            feedback.accept(Component.literal("[ClientCraft] Snapshot dump failed: " + e));
        }
    }
}
