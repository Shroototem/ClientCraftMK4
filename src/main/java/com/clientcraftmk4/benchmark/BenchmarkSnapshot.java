package com.clientcraftmk4.benchmark;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A captured dump of the client's crafting recipe collections plus the membership
 * of every item tag those recipes reference. It is written in-game via {@code /ccdump}
 * (see {@link BenchmarkSnapshotDumper}) and replayed headlessly by the benchmark harness,
 * so the resolver pipeline can be measured without a running client.
 *
 * <p>Each recipe is serialized as its {@link RecipeDisplayId} plus its
 * {@link RecipeDisplay} using Minecraft's own network stream codecs. We deliberately do
 * <em>not</em> serialize the full {@link RecipeDisplayEntry} (via its stream codec): that
 * also encodes {@code craftingRequirements} as {@code Ingredient}s, whose {@code HolderSet}
 * decode resolves item tags against the registry — which fails headlessly because tags are
 * datapack-driven and unbound. The resolver only reads {@code id()} and {@code display()},
 * and a {@code TagSlotDisplay} inside a display stores just the {@link TagKey} (no
 * resolution), so id + display is sufficient and safe to read back. Tag membership is
 * stored explicitly for the same reason (bindings unavailable from a bare bootstrap).
 */
public final class BenchmarkSnapshot {

    /** One inner list per {@code RecipeCollection}, preserving the recipe-book grouping. */
    public final List<List<RecipeDisplayEntry>> collections;
    /** Referenced item tag → its members (resolved against the live registry at dump time). */
    public final Map<TagKey<Item>, List<Item>> tagMembers;

    public BenchmarkSnapshot(List<List<RecipeDisplayEntry>> collections, Map<TagKey<Item>, List<Item>> tagMembers) {
        this.collections = collections;
        this.tagMembers = tagMembers;
    }

    public int recipeCount() {
        int n = 0;
        for (List<RecipeDisplayEntry> c : collections) n += c.size();
        return n;
    }

    public void write(Path path, RegistryAccess registries) throws IOException {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        buf.writeVarInt(collections.size());
        for (List<RecipeDisplayEntry> coll : collections) {
            buf.writeVarInt(coll.size());
            for (RecipeDisplayEntry entry : coll) {
                RecipeDisplayId.STREAM_CODEC.encode(buf, entry.id());
                RecipeDisplay.STREAM_CODEC.encode(buf, entry.display());
            }
        }

        buf.writeVarInt(tagMembers.size());
        for (Map.Entry<TagKey<Item>, List<Item>> e : tagMembers.entrySet()) {
            buf.writeIdentifier(e.getKey().location());
            buf.writeVarInt(e.getValue().size());
            for (Item item : e.getValue()) buf.writeIdentifier(BuiltInRegistries.ITEM.getKey(item));
        }

        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        Files.write(path, bytes);
    }

    public static BenchmarkSnapshot read(Path path, RegistryAccess registries) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries);

        // Category is required by the RecipeDisplayEntry constructor but never read by
        // the resolver; reuse any registered value as a placeholder.
        RecipeBookCategory placeholderCategory =
                BuiltInRegistries.RECIPE_BOOK_CATEGORY.stream().findFirst().orElseThrow();

        int collCount = buf.readVarInt();
        List<List<RecipeDisplayEntry>> collections = new ArrayList<>(collCount);
        for (int i = 0; i < collCount; i++) {
            int entryCount = buf.readVarInt();
            List<RecipeDisplayEntry> entries = new ArrayList<>(entryCount);
            for (int j = 0; j < entryCount; j++) {
                RecipeDisplayId id = RecipeDisplayId.STREAM_CODEC.decode(buf);
                RecipeDisplay display = RecipeDisplay.STREAM_CODEC.decode(buf);
                entries.add(new RecipeDisplayEntry(id, display, OptionalInt.empty(),
                        placeholderCategory, Optional.empty()));
            }
            collections.add(entries);
        }

        int tagCount = buf.readVarInt();
        Map<TagKey<Item>, List<Item>> tagMembers = new HashMap<>(tagCount * 2);
        for (int i = 0; i < tagCount; i++) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, buf.readIdentifier());
            int itemCount = buf.readVarInt();
            List<Item> items = new ArrayList<>(itemCount);
            for (int j = 0; j < itemCount; j++) items.add(BuiltInRegistries.ITEM.getValue(buf.readIdentifier()));
            tagMembers.put(tag, items);
        }
        return new BenchmarkSnapshot(collections, tagMembers);
    }
}
