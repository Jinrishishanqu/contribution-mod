package cn.contribution.stock;

import cn.contribution.industry.BuiltInIndustry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable stock-only mappings imported from the reviewed workbook and loaded once. */
public final class StockCatalog {
    public record Candidate(String itemId, String name, BuiltInIndustry industry) {}

    private static final List<Candidate> ALL = load();
    private static final Map<BuiltInIndustry, List<Candidate>> GROUPS = groups();

    private StockCatalog() {}

    public static List<Candidate> all() {
        return ALL;
    }

    static List<Candidate> forIndustry(BuiltInIndustry industry) {
        return GROUPS.get(industry);
    }

    private static List<Candidate> load() {
        Set<String> known = new HashSet<>();
        read("registry_catalog.json")
                .getAsJsonArray("items")
                .forEach(value -> known.add(value.getAsString()));
        return parse(read("stock_industry_map.json"), read("stock_item_names.json"), known);
    }

    private static JsonObject read(String filename) {
        String resource = "/data/contribution/contribution/" + filename;
        try (var input = StockCatalog.class.getResourceAsStream(resource)) {
            if (input == null)
                throw new IllegalStateException("Missing stock resource: " + resource);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load stock resource: " + resource, error);
        }
    }

    static List<Candidate> parse(JsonObject mapping, JsonObject names, Set<String> known) {
        if (mapping.size() != BuiltInIndustry.values().length)
            throw new IllegalArgumentException(
                    "Stock catalog must contain exactly nine industries");
        List<Candidate> values = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        for (BuiltInIndustry industry : BuiltInIndustry.values()) {
            String id = "contribution:" + industry.path();
            if (!mapping.has(id)
                    || !mapping.get(id).isJsonArray()
                    || mapping.getAsJsonArray(id).size() < 2)
                throw new IllegalArgumentException("Invalid or undersized stock industry: " + id);
            for (var entry : mapping.getAsJsonArray(id)) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Stock item ID must be a string: " + id);
                String itemId = entry.getAsString();
                if (!known.contains(itemId) || !assigned.add(itemId))
                    throw new IllegalArgumentException(
                            "Unknown or duplicate stock item: " + itemId);
                if (!names.has(itemId)
                        || !names.get(itemId).isJsonPrimitive()
                        || !names.getAsJsonPrimitive(itemId).isString()
                        || names.get(itemId).getAsString().isBlank())
                    throw new IllegalArgumentException("Missing stock item name: " + itemId);
                values.add(new Candidate(itemId, names.get(itemId).getAsString(), industry));
            }
        }
        if (!assigned.equals(names.keySet()))
            throw new IllegalArgumentException("Stock name keys differ from stock candidate IDs");
        return List.copyOf(values);
    }

    private static Map<BuiltInIndustry, List<Candidate>> groups() {
        var result = new EnumMap<BuiltInIndustry, List<Candidate>>(BuiltInIndustry.class);
        for (BuiltInIndustry industry : BuiltInIndustry.values())
            result.put(
                    industry, ALL.stream().filter(value -> value.industry() == industry).toList());
        return Map.copyOf(result);
    }
}
