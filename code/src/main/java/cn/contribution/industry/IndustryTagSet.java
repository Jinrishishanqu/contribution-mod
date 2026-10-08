package cn.contribution.industry;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public record IndustryTagSet(TagKey<Item> craft, TagKey<Block> place, TagKey<Block> mine) {}
