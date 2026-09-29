package cn.contribution.industry;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Validates packaged JSON and concrete registry memberships against the local 26.3 catalog. */
public final class ResourceChecks {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/resources");
        int count = 0;
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try (var reader = Files.newBufferedReader(file)) { JsonParser.parseReader(reader); count++; }
            }
        }
        JsonObject catalog;
        try (var reader = Files.newBufferedReader(root.resolve("data/contribution/contribution/registry_catalog.json"))) { catalog = JsonParser.parseReader(reader).getAsJsonObject(); }
        for (String action : List.of("craft", "place", "mine", "use", "interact")) {
            boolean item = action.equals("craft") || action.equals("use");
            Set<String> known = new HashSet<>();
            catalog.getAsJsonArray(item ? "items" : "blocks").forEach(value -> known.add(value.getAsString()));
            Set<String> assigned = new HashSet<>();
            for (BuiltInIndustry industry : BuiltInIndustry.values()) {
                Path tag = root.resolve("data/contribution/tags/" + (item ? "item/" : "block/") + action + "_" + industry.path() + ".json");
                try (var reader = Files.newBufferedReader(tag)) {
                    for (var value : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("values")) {
                        String id = value.getAsString();
                        if (!known.contains(id)) throw new AssertionError("Unknown registry ID " + tag + ": " + id);
                        if (!assigned.add(id)) throw new AssertionError("Duplicate industry membership " + action + ": " + id);
                    }
                }
            }
            if (action.equals("craft")) try (var reader = Files.newBufferedReader(root.resolve("data/contribution/contribution/reversible_craft_exclusions.json"))) {
                for (var value : JsonParser.parseReader(reader).getAsJsonArray())
                    if (assigned.contains(value.getAsString())) throw new AssertionError("Reversible craft item " + value);
            }
        }
        GameEventRules.loadBuiltIn();
        System.out.println("RESOURCES_PASS: " + count + " JSON files; all 45 player tags resolve without cross-industry duplicates");
    }
}
