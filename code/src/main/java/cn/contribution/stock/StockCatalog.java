package cn.contribution.stock;

import cn.contribution.industry.BuiltInIndustry;

import java.util.ArrayList;
import java.util.List;

/** Stable vanilla item identifiers; these are market symbols, not inventory-backed shares. */
public final class StockCatalog {
    public record Candidate(String itemId, String name, BuiltInIndustry industry) { }

    private static final List<Candidate> ALL = create();

    private StockCatalog() { }

    public static List<Candidate> all() { return ALL; }

    private static List<Candidate> create() {
        List<Candidate> values = new ArrayList<>(36);
        add(values, BuiltInIndustry.CONSTRUCTION_LANDSCAPING, "bricks:红砖", "glass:玻璃", "stone_bricks:石砖", "oak_sapling:橡树树苗");
        add(values, BuiltInIndustry.MINING_METALLURGY, "diamond:钻石", "iron_ingot:铁锭", "gold_ingot:金锭", "emerald:绿宝石");
        add(values, BuiltInIndustry.ENERGY_CHEMICAL, "coal:煤炭", "blaze_powder:烈焰粉", "redstone:红石粉", "gunpowder:火药");
        add(values, BuiltInIndustry.PROCESSING_MANUFACTURING, "piston:活塞", "hopper:漏斗", "observer:侦测器", "furnace:熔炉");
        add(values, BuiltInIndustry.TECHNOLOGY_MAGIC, "ender_pearl:末影珍珠", "enchanting_table:附魔台", "amethyst_shard:紫水晶碎片", "ender_eye:末影之眼");
        add(values, BuiltInIndustry.AGRICULTURE_FORESTRY_LIVESTOCK_FISHERY, "wheat:小麦", "carrot:胡萝卜", "leather:皮革", "cod:鳕鱼");
        add(values, BuiltInIndustry.MILITARY_FOOD_MEDICINE, "iron_sword:铁剑", "shield:盾牌", "bread:面包", "golden_apple:金苹果");
        add(values, BuiltInIndustry.CIRCULATION_SERVICES, "chest:箱子", "minecart:矿车", "elytra:鞘翅", "oak_boat:橡木船");
        add(values, BuiltInIndustry.CULTURE_EDUCATION_LIVELIHOOD, "book:书", "compass:指南针", "clock:时钟", "painting:画");
        return List.copyOf(values);
    }

    private static void add(List<Candidate> result, BuiltInIndustry industry, String... items) {
        for (String item : items) {
            String[] parts = item.split(":", 2);
            result.add(new Candidate("minecraft:" + parts[0], parts[1], industry));
        }
    }
}
