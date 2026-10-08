package cn.contribution.mixin;

import cn.contribution.industry.ResultTransferContext;
import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;

/** These are the pinned 26.3 result slots, not every slot's generic onTake method. */
@Mixin(
        targets = {
            "net.minecraft.world.inventory.StonecutterMenu$2",
            "net.minecraft.world.inventory.LoomMenu$6"
        })
public abstract class WorkshopResultMixin {
    @WrapMethod(method = "onTake")
    private void contribution$completed(Player player, ItemStack result, Operation<Void> original) {
        ItemStack transferred = ResultTransferContext.result(result).copy();
        original.call(player, result);
        var service = ContributionRuntime.statistics();
        if (player instanceof ServerPlayer actor && service != null && !transferred.isEmpty()) {
            String event =
                    getClass().getName().endsWith("StonecutterMenu$2")
                            ? "contribution:process/stonecutting"
                            : "contribution:modify/loom";
            service.gameEvent(actor.level().getServer(), event, transferred.getCount(), actor);
        }
    }
}
