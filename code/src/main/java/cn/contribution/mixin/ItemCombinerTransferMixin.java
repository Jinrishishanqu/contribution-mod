package cn.contribution.mixin;

import cn.contribution.industry.ResultTransferContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemCombinerMenu.class)
public abstract class ItemCombinerTransferMixin {
    @WrapMethod(method = "quickMoveStack")
    private ItemStack contribution$resultTransfer(Player player, int index, Operation<ItemStack> original) {
        ItemCombinerMenu menu = (ItemCombinerMenu)(Object)this;
        if (index != menu.getResultSlot()) return original.call(player, index);
        var previous = ResultTransferContext.begin(menu.getSlot(index).getItem());
        try { return original.call(player, index); }
        finally { ResultTransferContext.restore(previous); }
    }
}
