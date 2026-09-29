package cn.contribution.industry;

import net.minecraft.world.item.ItemStack;

/** Preserves the result actually transferred by a shift-click after vanilla empties its stack. */
public final class ResultTransferContext {
    private static final ThreadLocal<Transfer> CURRENT = new ThreadLocal<>();
    private ResultTransferContext() { }
    public static Transfer begin(ItemStack stack) {
        Transfer previous = CURRENT.get();
        CURRENT.set(new Transfer(stack.copy(), stack));
        return previous;
    }
    public static void restore(Transfer previous) {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
    public static ItemStack result(ItemStack normalResult) {
        Transfer transfer = CURRENT.get();
        return transfer == null ? normalResult : transfer.before.copyWithCount(Math.max(0, transfer.before.getCount() - transfer.remaining.getCount()));
    }
    public record Transfer(ItemStack before, ItemStack remaining) { }
}
