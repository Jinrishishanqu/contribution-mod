package cn.contribution.config;

import cn.contribution.industry.BuiltInIndustry;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RewardConfig {
    public int developmentIntervalSeconds = 300;
    public Map<String, String> developmentWeights = defaultWeights();
    public String timeZone = "Asia/Shanghai";
    public int dailyRequiredSeconds = 600;
    public int[] dailyCycleRewards = {10, 10, 10, 10, 15, 25, 25};
    public ShopOffer[] shopOffers = {
            new ShopOffer("bread", "面包", "minecraft:bread", 4, 20),
            new ShopOffer("torch", "火把", "minecraft:torch", 16, 20),
            new ShopOffer("iron_pickaxe", "铁镐", "minecraft:iron_pickaxe", 1, 80)
    };

    private static Map<String, String> defaultWeights() {
        Map<String, String> weights = new LinkedHashMap<>();
        for (BuiltInIndustry industry : BuiltInIndustry.values()) weights.put(industry.path(), "0.0001");
        return weights;
    }

    public static final class ShopOffer {
        public String id;
        public String name;
        public String itemId;
        public int itemCount;
        public int price;
        public ShopOffer() { }
        public ShopOffer(String id, String name, String itemId, int itemCount, int price) {
            this.id = id; this.name = name; this.itemId = itemId; this.itemCount = itemCount; this.price = price;
        }
    }
}
