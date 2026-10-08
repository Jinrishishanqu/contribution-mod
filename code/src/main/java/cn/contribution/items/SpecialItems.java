package cn.contribution.items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/** Server-only completion hooks; no ticking, temporary entities, shared storage or commands. */
public final class SpecialItems {
    private SpecialItems() {}

    public static void consumed(ServerPlayer player, ItemStack before, int consumed) {
        var data = before.get(DataComponents.CUSTOM_DATA);
        if ((consumed <= 0 && !player.getAbilities().instabuild)
                || !before.is(Items.FIREWORK_STAR)
                || data == null
                || !data.copyTag().getString("contribution:item").orElse("").equals("speaker"))
            return;
        ItemStack shown = player.getOffhandItem();
        Component message =
                Component.literal("[公示] ")
                        .append(player.getDisplayName())
                        .append(" 展示了 ")
                        .append(shown.isEmpty() ? Component.literal("空手") : shown.getDisplayName())
                        .append(shown.isEmpty() ? "" : " ×" + shown.getCount());
        player.level().getServer().getPlayerList().broadcastSystemMessage(message, false);
    }

    public static void collectHead(ServerPlayer victim, DamageSource damage, boolean successful) {
        if (!successful
                || !(damage.getDirectEntity() instanceof ServerPlayer attacker)
                || attacker == victim
                || !attacker.getMainHandItem().is(ItemTags.PICKAXES)
                || !attacker.getOffhandItem().is(ItemTags.SKULLS)) return;
        var silk =
                victim.registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT)
                        .getOrThrow(Enchantments.SILK_TOUCH);
        if (EnchantmentHelper.getItemEnchantmentLevel(silk, attacker.getMainHandItem()) < 1) return;
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(victim.getGameProfile()));
        attacker.getOffhandItem().shrink(1);
        if (!attacker.getInventory().add(head))
            attacker.drop(head, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }
}
