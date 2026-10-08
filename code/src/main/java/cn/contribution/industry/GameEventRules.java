package cn.contribution.industry;

import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class GameEventRules {
    private static final Set<String> MEASURES =
            Set.of(
                    "output_item",
                    "modified_item",
                    "successful_action",
                    "entity",
                    "completed_trade",
                    "distance_block");

    public record Rule(BuiltInIndustry industry, String measure, int unitSize, int unitValue) {}

    private final Map<String, Rule> rules;

    public GameEventRules(Map<String, Rule> rules) {
        this.rules = Map.copyOf(rules);
    }

    public static GameEventRules loadBuiltIn() {
        return BuiltInHolder.RULES;
    }

    /** The packaged mapping is immutable; /reload overlays are read separately by RuleSnapshot. */
    private static final class BuiltInHolder {
        private static final GameEventRules RULES = readBuiltIn();
    }

    private static GameEventRules readBuiltIn() {
        String path = "/data/contribution/contribution/game_event_industry_map.json";
        try (var stream = GameEventRules.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing built-in game event mapping");
            }
            var root =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            Map<String, Rule> map = new HashMap<>();
            for (var entry : root.getAsJsonArray("events")) {
                var value = entry.getAsJsonObject();
                String eventId = value.get("event_id").getAsString();
                String industryId = value.get("industry").getAsString();
                BuiltInIndustry industry = null;
                for (BuiltInIndustry candidate : BuiltInIndustry.values()) {
                    if (industryId.equals("contribution:" + candidate.path())) {
                        industry = candidate;
                        break;
                    }
                }
                int unitSize = value.get("unit_size").getAsInt();
                int unitValue = value.get("unit_value").getAsInt();
                String measure = value.get("measure").getAsString();
                if (industry == null
                        || !MEASURES.contains(measure)
                        || unitSize <= 0
                        || unitValue < 0) {
                    throw new IllegalStateException("Invalid game event rule: " + eventId);
                }
                if (map.putIfAbsent(eventId, new Rule(industry, measure, unitSize, unitValue))
                        != null) {
                    throw new IllegalStateException("Duplicate game event: " + eventId);
                }
            }
            return new GameEventRules(map);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot load built-in game event mapping", error);
        }
    }

    public Rule rule(String eventId) {
        return rules.get(eventId);
    }

    public Map<String, Rule> entries() {
        return rules;
    }
}
