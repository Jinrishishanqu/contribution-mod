package cn.contribution.mixin;

import cn.contribution.industry.ShearingContext;
import cn.contribution.runtime.ContributionRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BiConsumer;
import java.util.function.Function;

@Mixin(LivingEntity.class)
public abstract class LivingEntityShearingMixin {
    @WrapOperation(method = "dropFromShearingLootTable", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Function;Ljava/util/function/BiConsumer;)Z"))
    private boolean contribution$afterShearingLoot(LivingEntity entity, ServerLevel level,
            ResourceKey<LootTable> lootTable, Function<LootParams.Builder, LootParams> params,
            BiConsumer<ServerLevel, ItemStack> output, Operation<Boolean> original) {
        long[] produced = {0};
        BiConsumer<ServerLevel, ItemStack> countingOutput = (destination, stack) -> {
            int count = stack.getCount();
            output.accept(destination, stack);
            produced[0] += count;
        };
        boolean yielded = original.call(entity, level, lootTable, params, countingOutput);
        if (yielded && produced[0] > 0 && ContributionRuntime.statistics() != null) {
            ServerPlayer actor = ShearingContext.actorFor(entity);
            ContributionRuntime.statistics().gameEvent(level.getServer(),
                    "contribution:entity/shear", produced[0], actor);
        }
        return yielded;
    }
}
