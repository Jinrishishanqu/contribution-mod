package cn.contribution.shop;

import cn.contribution.account.AccountService;
import cn.contribution.api.*;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.*;
import cn.contribution.network.*;
import cn.contribution.reward.RewardConfigGuard;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import java.nio.file.*;
import java.util.UUID;

/** Native component parsing plus the complete catalog/order/mailbox persistence path. */
public final class ItemComponentChecks {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries =
                VanillaRegistries.createReloadableLookup(VanillaRegistries.createWorldLookup());
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(registries)
                .forEach(
                        net.minecraft.core.component.DataComponentInitializers.PendingComponents
                                ::apply);
        String spec =
                "minecraft:diamond_sword[minecraft:custom_name={text:'荣誉剑'},minecraft:damage=7,"
                    + "minecraft:enchantments={'minecraft:unbreaking':3},minecraft:custom_data={owner:'tester'}]";
        var stack = ItemStackSpec.parse(registries, spec);
        if (!stack.getHoverName().getString().equals("荣誉剑")
                || stack.getDamageValue() != 7
                || !stack.has(DataComponents.CUSTOM_DATA)
                || stack.get(DataComponents.ENCHANTMENTS).isEmpty())
            throw new AssertionError("Native item parser lost components");
        var removed =
                ItemStackSpec.parse(
                        registries, "minecraft:diamond_sword[!minecraft:attribute_modifiers]");
        if (removed.has(DataComponents.ATTRIBUTE_MODIFIERS))
            throw new AssertionError("Removed component restored");
        try {
            ItemStackSpec.parse(registries, "minecraft:diamond_sword[minecraft:not_a_component=3]");
            throw new AssertionError("Invalid component accepted");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
        }
        try {
            ItemStackSpec.parse(registries, spec + " trailing");
            throw new AssertionError("Trailing data accepted");
        } catch (IllegalArgumentException expected) {
        }
        ServerConfig config = new ServerConfig();
        config.database.mode = "embedded";
        Path directory = Files.createTempDirectory(Path.of("build"), "item-components-");
        try (DatabaseService database =
                new DatabaseService(config, directory.resolve("contribution"))) {
            if (database.start().join() != DatabaseState.AVAILABLE)
                throw new AssertionError("V18 migration");
            RewardConfigGuard.initialize(database, config).join();
            var service = new ShopService(database, config);
            var created =
                    service.create(
                                    new ShopCatalog.Change(
                                            "组件商品",
                                            "minecraft:diamond_sword",
                                            2,
                                            5,
                                            "说明",
                                            true,
                                            0,
                                            spec))
                            .join();
            if (!created.success()) throw new AssertionError(created.message());
            var read = service.offer(created.id()).join();
            if (!read.itemSpec().equals(spec))
                throw new AssertionError("Catalog lost component specification");
            UUID player = UUID.randomUUID(), order = UUID.randomUUID();
            var accounts = new AccountService(database, "components-test");
            accounts.registerPlayer(player, "ComponentTester").join();
            accounts.changeBalance(
                            new BalanceChangeRequest(
                                    UUID.randomUUID(),
                                    AccountTarget.byUuid(player),
                                    100,
                                    BalanceChangeType.EXTERNAL,
                                    Identifier.parse("contribution:test"),
                                    "test",
                                    ""))
                    .join();
            if (!service.buy(player, String.valueOf(created.id()), 2, order)
                    .join()
                    .startsWith("购买成功")) throw new AssertionError("Purchase rejected");
            service.modify(
                            created.id(),
                            new ShopCatalog.Change(
                                    null,
                                    "minecraft:diamond_sword",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    "minecraft:diamond_sword"),
                            null)
                    .join();
            String pending =
                    database.transaction(
                                    connection -> {
                                        try (var query =
                                                connection.prepareStatement(
                                                        "SELECT item_spec, item_count FROM"
                                                            + " reward_delivery WHERE player_uuid ="
                                                            + " ?")) {
                                            query.setBytes(1, AccountService.uuidBytes(player));
                                            try (var row = query.executeQuery()) {
                                                if (!row.next() || row.getInt(2) != 4)
                                                    throw new AssertionError("Order item count");
                                                return row.getString(1);
                                            }
                                        }
                                    })
                            .join();
            if (!ItemStackSpec.parse(registries, pending)
                    .getComponentsPatch()
                    .equals(stack.getComponentsPatch()))
                throw new AssertionError("Order component snapshot changed after catalog editing");
            if (!service.buy(player, String.valueOf(created.id()), 2, order).join().contains("已提交"))
                throw new AssertionError("Order retry is not idempotent");
        }
        var version = new VersionPayload("0.1.5", "26.3");
        if (!VersionCompatibility.matches(version, version)
                || VersionCompatibility.matches(version, new VersionPayload("0.1.4", "26.3"))
                || VersionCompatibility.matches(version, new VersionPayload("0.1.5", "26.2")))
            throw new AssertionError("Version comparison");
        System.out.println(
                "ITEM_COMPONENTS_PASS: native component/removal syntax, invalid input, V18,"
                        + " catalog, immutable delivery snapshot, retry and versions");
    }
}
