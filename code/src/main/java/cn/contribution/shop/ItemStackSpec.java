package cn.contribution.shop;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Uses the same item/component parser as vanilla /item; no custom NBT interpretation. */
public final class ItemStackSpec {
    private ItemStackSpec() {}

    /**
     * Captures the component patch, including explicitly removed defaults, without consuming it.
     */
    public static String serialize(HolderLookup.Provider registries, ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Cannot list an empty hand");
        var tag =
                net.minecraft.core.component.DataComponentPatch.CODEC
                        .encodeStart(
                                registries.createSerializationContext(
                                        net.minecraft.nbt.NbtOps.INSTANCE),
                                stack.getComponentsPatch())
                        .getOrThrow();
        if (!(tag instanceof net.minecraft.nbt.CompoundTag components))
            throw new IllegalArgumentException("Invalid component patch");
        String id =
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem())
                        .toString();
        var entries =
                components.entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .map(
                                entry ->
                                        entry.getKey().startsWith("!")
                                                ? entry.getKey()
                                                : entry.getKey() + "=" + entry.getValue())
                        .toList();
        String spec = id + (entries.isEmpty() ? "" : "[" + String.join(",", entries) + "]");
        if (spec.length() > 2048)
            throw new IllegalArgumentException("物品组件定义最多 2048 个字符，请减少过长描述或内容");
        try {
            if (!ItemStack.isSameItemSameComponents(stack, parse(registries, spec)))
                throw new IllegalArgumentException("物品组件无法无损上架");
        } catch (CommandSyntaxException invalid) {
            throw new IllegalArgumentException(invalid.getMessage());
        }
        return spec;
    }

    public static ItemStack parse(HolderLookup.Provider registries, String spec)
            throws CommandSyntaxException {
        if (spec == null || spec.length() > 2048)
            throw new IllegalArgumentException("Invalid item specification length");
        StringReader reader = new StringReader(spec);
        var input = new ItemParser(registries).parse(reader);
        if (reader.canRead()) throw new IllegalArgumentException("Trailing item data");
        return input.createItemStack(1);
    }
}
