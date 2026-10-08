package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayerGameMode.class)
public abstract class BlockInteractionCompletionMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult contribution$interaction(
            ServerPlayer player,
            Level level,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hit,
            Operation<InteractionResult> original) {
        var before = level.getBlockState(hit.getBlockPos());
        int count = stack.getCount();
        var used = stack.getItem();
        boolean axe = stack.is(ItemTags.AXES);
        boolean placing = stack.getItem() instanceof BlockItem;
        InteractionResult result = original.call(player, level, stack, hand, hit);
        var after = level.getBlockState(hit.getBlockPos());
        var service = ContributionRuntime.statistics();
        if (service != null && !placing) {
            String event = null;
            if (used == Items.BONE_MEAL && stack.getCount() < count) event = "modify/bone_meal";
            else if (before.is(BlockTags.BEEHIVES)
                    && before.getValue(BeehiveBlock.HONEY_LEVEL) == 5
                    && after.is(BlockTags.BEEHIVES)
                    && after.getValue(BeehiveBlock.HONEY_LEVEL) == 0) event = "entity/honey";
            else if (before.is(Blocks.COMPOSTER)
                    && after.is(Blocks.COMPOSTER)
                    && before.getValue(ComposterBlock.LEVEL) == 8
                    && after.getValue(ComposterBlock.LEVEL) == 0) event = "modify/compost";
            else if (used == Items.BUCKET
                    && before.is(Blocks.LAVA)
                    && player.getItemInHand(hand).is(Items.LAVA_BUCKET))
                event = "collect/lava_bucket";
            else if (axe
                    && before.getBlock() != after.getBlock()
                    && BuiltInRegistries.BLOCK
                            .getKey(after.getBlock())
                            .getPath()
                            .startsWith("stripped_")) event = "modify/strip_log";
            if (event != null) {
                service.gameEvent(player.level().getServer(), "contribution:" + event, 1, player);
                return result;
            }
            // These outcomes are counted by their successful vanilla statistic, not interaction.
            if (before.is(BlockTags.CAULDRONS) || after.is(BlockTags.FLOWER_POTS)) return result;
        }
        if (!placing
                && ContributionRuntime.statistics() != null
                && (before != level.getBlockState(hit.getBlockPos()) || stack.getCount() < count))
            ContributionRuntime.statistics().interacted(player, hit.getBlockPos(), before);
        return result;
    }
}
