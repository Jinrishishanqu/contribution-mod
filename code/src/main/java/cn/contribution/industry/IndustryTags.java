package cn.contribution.industry;

import cn.contribution.ContributionMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class IndustryTags {
    private IndustryTags() {
    }

    public static IndustryTagSet forIndustry(BuiltInIndustry industry) {
        return new IndustryTagSet(
                item("craft_" + industry.path()),
                block("place_" + industry.path()),
                block("mine_" + industry.path())
        );
    }

    private static TagKey<Item> item(String path) {
        return TagKey.create(Registries.ITEM, id(path));
    }

    private static TagKey<Block> block(String path) {
        return TagKey.create(Registries.BLOCK, id(path));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ContributionMod.MOD_ID, path);
    }
}
