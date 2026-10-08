package cn.contribution.items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;

/** Merge our single-line upgrade descriptions without replacing unrelated lore or components. */
public final class SmithingUpgradeLore {
    private SmithingUpgradeLore() {}

    public static void merge(ItemStack base, ItemStack output, ItemLore declared) {
        if (output.isEmpty() || declared == null || declared.lines().size() != 1) return;
        var marker = declared.lines().getFirst();
        String text = marker.getString();
        if (!text.equals("添翼") && !text.equals("下界合金强化")) return;
        var lines = new ArrayList<>(base.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        if (lines.stream().noneMatch(line -> line.getString().equals(text))
                && lines.size() < ItemLore.MAX_LINES) lines.add(marker);
        output.set(DataComponents.LORE, new ItemLore(lines));
    }
}
