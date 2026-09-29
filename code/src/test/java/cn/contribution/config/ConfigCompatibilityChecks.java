package cn.contribution.config;

import com.google.gson.JsonParser;

public final class ConfigCompatibilityChecks {
    public static void main(String[] args) {
        require("embedded", "{}");
        require("embedded", "{\"database\":{\"enabled\":false}}");
        require("mysql", "{\"database\":{\"enabled\":true}}");
        require("embedded", "{\"database\":{\"mode\":\"embedded\",\"enabled\":true}}");
        require("mysql", "{\"database\":{\"mode\":\"mysql\",\"enabled\":false}}");
        System.out.println("CONFIG_COMPATIBILITY_PASS: new defaults and legacy enabled switch");
    }

    private static void require(String expected, String json) {
        String actual = ConfigLoader.parse(JsonParser.parseString(json).getAsJsonObject()).database.mode;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
