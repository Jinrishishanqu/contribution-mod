package cn.contribution.runtime;

final class MixinLinkageCheck {
    private static final String[] TARGETS = {
            "net.minecraft.world.item.BlockItem",
            "net.minecraft.world.inventory.ResultSlot",
            "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity",
            "net.minecraft.world.level.block.CrafterBlock",
            "net.minecraft.world.inventory.MerchantResultSlot",
            "net.minecraft.world.entity.animal.Animal",
            "net.minecraft.world.entity.TamableAnimal",
            "net.minecraft.world.entity.projectile.FishingHook",
            "net.minecraft.world.inventory.SmithingMenu",
            "net.minecraft.world.inventory.AnvilMenu",
            "net.minecraft.world.inventory.ItemCombinerMenu",
            "net.minecraft.world.inventory.EnchantmentMenu",
            "net.minecraft.world.level.block.entity.BrewingStandBlockEntity",
            "net.minecraft.world.entity.monster.zombie.ZombieVillager",
            "net.minecraft.world.entity.animal.cow.AbstractCow",
            "net.minecraft.world.entity.animal.goat.Goat",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.world.item.ItemStack",
            "net.minecraft.server.level.ServerPlayerGameMode"
    };

    private MixinLinkageCheck() {
    }

    static void verify() {
        ClassLoader loader = MixinLinkageCheck.class.getClassLoader();
        for (String target : TARGETS) {
            try {
                Class.forName(target, false, loader);
            } catch (ClassNotFoundException error) {
                throw new IllegalStateException("Missing Minecraft 26.3 Mixin target: " + target, error);
            }
        }
    }
}
