package cn.contribution.mixin;

import cn.contribution.industry.ResultTransferContext;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;

@Mixin({StonecutterMenu.class, LoomMenu.class, GrindstoneMenu.class})
public abstract class WorkshopTransferMixin {
    @WrapMethod(method = "quickMoveStack")
    private ItemStack contribution$transferred(
            Player player, int index, Operation<ItemStack> original) {
        var menu = (AbstractContainerMenu) (Object) this;
        int resultSlot = menu instanceof StonecutterMenu ? 1 : menu instanceof LoomMenu ? 3 : 2;
        if (index != resultSlot) return original.call(player, index);
        var previous = ResultTransferContext.begin(menu.getSlot(index).getItem());
        try {
            return original.call(player, index);
        } finally {
            ResultTransferContext.restore(previous);
        }
    }
}
