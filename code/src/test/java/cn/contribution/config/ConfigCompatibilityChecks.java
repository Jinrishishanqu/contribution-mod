package cn.contribution.config;

import com.google.gson.JsonParser;

public final class ConfigCompatibilityChecks {
    public static void main(String[] args) {
        require("embedded", "{}");
        require("embedded", "{\"database\":{\"enabled\":false}}");
        require("mysql", "{\"database\":{\"enabled\":true}}");
        require("embedded", "{\"database\":{\"mode\":\"embedded\",\"enabled\":true}}");
        require("mysql", "{\"database\":{\"mode\":\"mysql\",\"enabled\":false}}");
        var root = new com.google.gson.JsonObject();
        var rewards = new com.google.gson.JsonObject();
        var weights = new com.google.gson.JsonObject();
        root.add("rewards", rewards);
        rewards.add("developmentWeights", weights);
        for (var industry : cn.contribution.industry.BuiltInIndustry.values()) {
            weights.addProperty(industry.path(), "0.001");
            if (!new RewardConfig().developmentWeights.get(industry.path()).equals("0.004"))
                throw new AssertionError("default weight must be 1/250");
        }
        if (!ConfigLoader.hasLegacyDefaultWeights(root))
            throw new AssertionError("old uniform default upgrades");
        weights.addProperty(cn.contribution.industry.BuiltInIndustry.values()[0].path(), "0.0001");
        if (ConfigLoader.hasLegacyDefaultWeights(root))
            throw new AssertionError("mixed customized weights preserved");
        System.out.println("CONFIG_COMPATIBILITY_PASS: new defaults and legacy enabled switch");
    }

    private static void require(String expected, String json) {
        String actual =
                ConfigLoader.parse(JsonParser.parseString(json).getAsJsonObject()).database.mode;
        if (!expected.equals(actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
