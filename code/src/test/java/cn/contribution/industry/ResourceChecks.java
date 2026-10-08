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
                try (var reader = Files.newBufferedReader(file)) {
                    JsonParser.parseReader(reader);
                    count++;
                }
            }
        }
        JsonObject catalog;
        try (var reader =
                Files.newBufferedReader(
                        root.resolve("data/contribution/contribution/registry_catalog.json"))) {
            catalog = JsonParser.parseReader(reader).getAsJsonObject();
        }
        for (String action : List.of("craft", "place", "mine", "use", "interact")) {
            boolean item = action.equals("craft") || action.equals("use");
            Set<String> known = new HashSet<>();
            catalog.getAsJsonArray(item ? "items" : "blocks")
                    .forEach(value -> known.add(value.getAsString()));
            Set<String> assigned = new HashSet<>();
            for (BuiltInIndustry industry : BuiltInIndustry.values()) {
                Path tag =
                        root.resolve(
                                "data/contribution/tags/"
                                        + (item ? "item/" : "block/")
                                        + action
                                        + "_"
                                        + industry.path()
                                        + ".json");
                try (var reader = Files.newBufferedReader(tag)) {
                    for (var value :
                            JsonParser.parseReader(reader)
                                    .getAsJsonObject()
                                    .getAsJsonArray("values")) {
                        String id = value.getAsString();
                        if (!known.contains(id))
                            throw new AssertionError("Unknown registry ID " + tag + ": " + id);
                        if (!assigned.add(id))
                            throw new AssertionError(
                                    "Duplicate industry membership " + action + ": " + id);
                    }
                }
            }
            if (action.equals("craft"))
                try (var reader =
                        Files.newBufferedReader(
                                root.resolve(
                                        "data/contribution/contribution/reversible_craft_exclusions.json"))) {
                    for (var value : JsonParser.parseReader(reader).getAsJsonArray())
                        if (assigned.contains(value.getAsString()))
                            throw new AssertionError("Reversible craft item " + value);
                }
            Integer expected = Map.of("craft", 1065, "place", 1206, "mine", 1129).get(action);
            if (expected != null && assigned.size() != expected)
                throw new AssertionError(
                        "Definition row count mismatch " + action + ": " + assigned.size());
        }
        GameEventRules builtIn = GameEventRules.loadBuiltIn();
        JsonObject manifest =
                JsonParser.parseString(
                                Files.readString(
                                        root.resolve(
                                                "data/contribution/contribution/definition_manifest.json")))
                        .getAsJsonObject();
        for (var source : manifest.getAsJsonArray("sources")) {
            var definition = source.getAsJsonObject();
            var workbook = Path.of("..").resolve(definition.get("file").getAsString());
            String actual =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(workbook)));
            if (!actual.equals(definition.get("sha256").getAsString()))
                throw new AssertionError("XLSX changed without definition sync: " + workbook);
        }
        for (var entry : manifest.getAsJsonArray("events")) {
            var definition = entry.getAsJsonObject();
            var rule = builtIn.rule(definition.get("event_id").getAsString());
            if (rule == null || definition.get("industry").getAsString().contains("；")) continue;
            if (!("contribution:" + rule.industry().path())
                            .equals(definition.get("industry").getAsString())
                    || rule.unitSize() != definition.get("unit_size").getAsInt()
                    || rule.unitValue() != definition.get("unit_value").getAsInt())
                throw new AssertionError("Enabled event differs from reviewed target: " + entry);
        }
        for (var sector : BuiltInIndustry.values()) {
            var rule = builtIn.rule("contribution:craft/" + sector.path());
            if (rule == null || rule.unitSize() != 64 || rule.unitValue() != 1)
                throw new AssertionError("Craft unit must be 64 outputs");
        }
        for (var mode :
                List.of(
                        "elytra",
                        "horse",
                        "boat",
                        "minecart",
                        "pig",
                        "strider",
                        "happy_ghast",
                        "nautilus"))
            if (builtIn.rule("contribution:logistics/distance/" + mode).unitSize() != 10)
                throw new AssertionError("Travel unit must be 10 blocks");
        if (builtIn != GameEventRules.loadBuiltIn())
            throw new AssertionError("Built-in rules must be shared");
        try {
            builtIn.entries().clear();
            throw new AssertionError("Built-in rules must be immutable");
        } catch (UnsupportedOperationException expected) {
            // External resource overlays are validated separately; this packaged map cannot change.
        }
        System.out.println(
                "RESOURCES_PASS: "
                        + count
                        + " JSON files; all 45 player tags resolve without cross-industry"
                        + " duplicates");
    }
}
