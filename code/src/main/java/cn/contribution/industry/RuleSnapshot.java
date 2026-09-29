package cn.contribution.industry;

import com.google.gson.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Frozen registry membership: vanilla /reload cannot change the active accounting rules mid-day. */
public final class RuleSnapshot {
    private static final Gson JSON = new Gson();
    public final Map<String, Map<String, String>> actions = new TreeMap<>();
    public final Map<String, String> production = new TreeMap<>();
    public final Map<String, GameEventRules.Rule> events = new TreeMap<>();
    public static RuleSnapshot capture(MinecraftServer server) {
        RuleSnapshot result = new RuleSnapshot();
        for (String action : List.of("craft", "place", "mine", "use", "interact")) {
            Map<String, String> members = new TreeMap<>();
            boolean items = action.equals("craft") || action.equals("use");
            for (BuiltInIndustry industry : BuiltInIndustry.values()) {
                Identifier id = Identifier.fromNamespaceAndPath("contribution", action + "_" + industry.path());
                if (items) {
                    var tag = TagKey.create(Registries.ITEM, id);
                    for (var item : BuiltInRegistries.ITEM) if (item.builtInRegistryHolder().is(tag))
                        add(members, BuiltInRegistries.ITEM.getKey(item).toString(), industry.name(), id.toString());
                } else {
                    var tag = TagKey.create(Registries.BLOCK, id);
                    for (var block : BuiltInRegistries.BLOCK) if (block.builtInRegistryHolder().is(tag))
                        add(members, BuiltInRegistries.BLOCK.getKey(block).toString(), industry.name(), id.toString());
                }
            }
            result.actions.put(action, members);
        }
        try (var stream = RuleSnapshot.class.getResourceAsStream("/data/contribution/contribution/reversible_craft_exclusions.json")) {
            if (stream == null) throw new IllegalStateException("Missing reversible crafting exclusions");
            try (var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
                for (var item : JsonParser.parseReader(reader).getAsJsonArray()) {
                    if (result.actions.get("craft").containsKey(item.getAsString()))
                        throw new IllegalArgumentException("craft tag contains reversible item " + item.getAsString() + "; remove it from the tag");
                }
            }
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot read reversible crafting exclusions", error); }
        for (String kind : List.of("mineral", "chemical", "material", "food")) {
            var tag = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("contribution", "process_furnace_" + kind));
            for (var item : BuiltInRegistries.ITEM) if (item.builtInRegistryHolder().is(tag))
                add(result.production, BuiltInRegistries.ITEM.getKey(item).toString(), "contribution:process/furnace/" + kind + "_output", tag.toString());
        }
        result.events.putAll(GameEventRules.loadBuiltIn().entries());
        try {
            var resource = server.getResourceManager().getResource(Identifier.fromNamespaceAndPath("contribution", "contribution/game_event_industry_map.json"));
            if (resource.isPresent()) try (var reader = resource.get().openAsReader()) {
                result.events.clear();
                for (var element : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("events")) {
                    var entry = element.getAsJsonObject();
                    String id = entry.get("event_id").getAsString();
                    Identifier.parse(id);
                    BuiltInIndustry industry = Arrays.stream(BuiltInIndustry.values()).filter(value -> ("contribution:" + value.path()).equals(entry.get("industry").getAsString())).findFirst().orElseThrow();
                    String measure = entry.get("measure").getAsString();
                    int size = entry.get("unit_size").getAsBigDecimal().intValueExact(), value = entry.get("unit_value").getAsBigDecimal().intValueExact();
                    var builtIn = GameEventRules.loadBuiltIn().rule(id);
                    if (builtIn == null || !builtIn.measure().equals(measure) || (!measure.equals("distance_block") && size != 1))
                        throw new IllegalArgumentException("game_event_industry_map.json events: unsupported event/measure/unit_size: " + id);
                    if (size <= 0 || value <= 0 || !Set.of("output_item","modified_item","successful_action","entity","completed_trade","distance_block").contains(measure)
                            || result.events.putIfAbsent(id, new GameEventRules.Rule(industry, measure, size, value)) != null)
                        throw new IllegalArgumentException("game_event_industry_map.json events: invalid/duplicate " + id);
                }
                if (!result.events.keySet().containsAll(GameEventRules.loadBuiltIn().entries().keySet()))
                    throw new IllegalArgumentException("game_event_industry_map.json: required built-in event missing");
            }
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot read game event rules", error); }
        return result;
    }
    private static void add(Map<String,String> map, String id, String value, String tag) {
        if (map.putIfAbsent(id, value) != null) throw new IllegalArgumentException("Tag " + tag + ": duplicate industry assignment for " + id + "; remove one assignment");
    }
    public Optional<BuiltInIndustry> match(String action, String id) {
        String value = actions.getOrDefault(action, Map.of()).get(id);
        return value == null ? Optional.empty() : Optional.of(BuiltInIndustry.valueOf(value));
    }
    public String json() { return JSON.toJson(this); }
    public static RuleSnapshot parse(String text) { return JSON.fromJson(text, RuleSnapshot.class); }
    public byte[] hash() {
        try { return MessageDigest.getInstance("SHA-256").digest(json().getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
