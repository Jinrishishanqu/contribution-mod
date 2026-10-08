package cn.contribution.items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Scope only the vanilla flight-wear path, never combat damage occurring during flight. */
public final class FlightDurability {
    private static final ThreadLocal<Boolean> FLIGHT = ThreadLocal.withInitial(() -> false);

    private FlightDurability() {}

    public static boolean enter() {
        boolean previous = FLIGHT.get();
        FLIGHT.set(true);
        return previous;
    }

    public static void leave(boolean previous) {
        if (previous) FLIGHT.set(true);
        else FLIGHT.remove();
    }

    public static ItemStack enchantmentInput(ItemStack stack) {
        var equipped = stack.get(DataComponents.EQUIPPABLE);
        return FLIGHT.get()
                        && !stack.is(Items.ELYTRA)
                        && stack.has(DataComponents.GLIDER)
                        && equipped != null
                        && equipped.slot() == net.minecraft.world.entity.EquipmentSlot.CHEST
                ? stack.transmuteCopy(Items.ELYTRA)
                : stack;
    }
}
