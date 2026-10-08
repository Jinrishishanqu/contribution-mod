package cn.contribution.mixin;

import cn.contribution.industry.ResultTransferContext;
import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;

import java.util.Objects;

@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @WrapMethod(method = "onTake")
    private void contribution$completedAnvil(
            Player player, ItemStack result, Operation<Void> original) {
        ItemStack before = ((AnvilMenu) (Object) this).getSlot(0).getItem().copy();
        ItemStack transferred = ResultTransferContext.result(result).copy();
        original.call(player, result);
        var service = ContributionRuntime.statistics();
        if (!(player instanceof ServerPlayer actor) || transferred.isEmpty() || service == null)
            return;
        // A combined repair/enchant/rename result has exactly one primary industry.
        String event =
                before.isDamageableItem()
                                && transferred.isDamageableItem()
                                && transferred.getDamageValue() < before.getDamageValue()
                        ? "anvil_repair"
                        : !Objects.equals(
                                        before.get(DataComponents.ENCHANTMENTS),
                                        transferred.get(DataComponents.ENCHANTMENTS))
                                ? "anvil_enchant"
                                : !Objects.equals(
                                                before.get(DataComponents.CUSTOM_NAME),
                                                transferred.get(DataComponents.CUSTOM_NAME))
                                        ? "anvil_rename"
                                        : null;
        if (event != null)
            service.gameEvent(
                    actor.level().getServer(),
                    "contribution:modify/" + event,
                    transferred.getCount(),
                    actor);
    }
}
