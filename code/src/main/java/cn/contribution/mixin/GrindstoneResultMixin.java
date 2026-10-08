package cn.contribution.mixin;

import cn.contribution.industry.ResultTransferContext;
import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Objects;

@Mixin(targets = "net.minecraft.world.inventory.GrindstoneMenu$4")
public abstract class GrindstoneResultMixin {
    @Shadow @Final private GrindstoneMenu this$0;

    @WrapMethod(method = "onTake")
    private void contribution$completed(Player player, ItemStack result, Operation<Void> original) {
        ItemStack first = this$0.getSlot(0).getItem().copy();
        ItemStack second = this$0.getSlot(1).getItem().copy();
        ItemStack transferred = ResultTransferContext.result(result).copy();
        original.call(player, result);
        var service = ContributionRuntime.statistics();
        if (!(player instanceof ServerPlayer actor) || service == null || transferred.isEmpty())
            return;
        boolean repaired =
                transferred.isDamageableItem()
                        && (first.isDamageableItem()
                                        && transferred.getDamageValue() < first.getDamageValue()
                                || second.isDamageableItem()
                                        && transferred.getDamageValue() < second.getDamageValue());
        boolean disenchanted =
                contribution$removedEnchantments(first, transferred)
                        || contribution$removedEnchantments(second, transferred);
        String event =
                repaired
                        ? "contribution:modify/grindstone"
                        : disenchanted ? "contribution:modify/grindstone_disenchant" : null;
        if (event != null)
            service.gameEvent(actor.level().getServer(), event, transferred.getCount(), actor);
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean contribution$removedEnchantments(ItemStack before, ItemStack after) {
        return !before.isEmpty()
                && (!Objects.equals(
                                before.get(DataComponents.ENCHANTMENTS),
                                after.get(DataComponents.ENCHANTMENTS))
                        || !Objects.equals(
                                before.get(DataComponents.STORED_ENCHANTMENTS),
                                after.get(DataComponents.STORED_ENCHANTMENTS)));
    }
}
