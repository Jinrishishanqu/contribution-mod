package cn.contribution.industry;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public final class IndustryMatcher {
    public enum Action { CRAFT, PLACE, MINE, USE, INTERACT }

    private IndustryMatcher() {
    }

    public static Optional<BuiltInIndustry> match(Action action, ItemStack item, BlockState block) {
        RuleSnapshot snapshot = RuleManager.current();
        if (snapshot != null) {
            String id = item != null ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString()
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            return snapshot.match(action.name().toLowerCase(java.util.Locale.ROOT), id);
        }
        for (var entry : IndustryRegistry.builtIns().entrySet()) {
            IndustryTagSet tags = entry.getValue();
            boolean matched = switch (action) {
                case CRAFT -> item != null && item.getItem().builtInRegistryHolder().is(tags.craft());
                case PLACE -> block != null && block.getBlock().builtInRegistryHolder().is(tags.place());
                case MINE -> block != null && block.getBlock().builtInRegistryHolder().is(tags.mine());
                case USE, INTERACT -> false;
            };
            if (matched) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
}
