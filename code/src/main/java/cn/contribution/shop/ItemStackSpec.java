package cn.contribution.shop;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Uses the same item/component parser as vanilla /item; no custom NBT interpretation. */
public final class ItemStackSpec {
    private ItemStackSpec() { }
    public static ItemStack parse(HolderLookup.Provider registries, String spec) throws CommandSyntaxException {
        if (spec == null || spec.length() > 2048) throw new IllegalArgumentException("Invalid item specification length");
        StringReader reader = new StringReader(spec);
        var input = new ItemParser(registries).parse(reader);
        if (reader.canRead()) throw new IllegalArgumentException("Trailing item data");
        return input.createItemStack(1);
    }
}
