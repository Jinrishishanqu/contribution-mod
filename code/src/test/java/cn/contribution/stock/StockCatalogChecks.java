package cn.contribution.stock;

import cn.contribution.industry.BuiltInIndustry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

final class StockCatalogChecks {
    static void verify() {
        var all = StockCatalog.all();
        try (var input =
                StockCatalogChecks.class.getResourceAsStream(
                        "/data/contribution/contribution/stock_catalog_manifest.json")) {
            var manifest =
                    com.google.gson.JsonParser.parseReader(
                                    new java.io.InputStreamReader(
                                            input, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            check(
                    all.size() == manifest.get("item_count").getAsInt(),
                    "reviewed stock candidates imported");
            for (var industry : BuiltInIndustry.values())
                check(
                        StockCatalog.forIndustry(industry).size()
                                == manifest.getAsJsonObject("industry_counts")
                                        .get("contribution:" + industry.path())
                                        .getAsInt(),
                        "reviewed industry count");
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        check(
                all.stream().map(StockCatalog.Candidate::itemId).distinct().count() == all.size(),
                "unique item IDs");
        var mapping = new JsonObject();
        var names = new JsonObject();
        var known = new HashSet<String>();
        for (var industry : BuiltInIndustry.values()) {
            var ids = new JsonArray();
            for (var candidate : StockCatalog.forIndustry(industry)) {
                ids.add(candidate.itemId());
                names.addProperty(candidate.itemId(), candidate.name());
                known.add(candidate.itemId());
            }
            mapping.add("contribution:" + industry.path(), ids);
        }
        check(StockCatalog.parse(mapping, names, known).equals(all), "catalog round trip");
        var first = all.getFirst();
        var key = "contribution:" + first.industry().path();
        var duplicate = mapping.deepCopy();
        duplicate.getAsJsonArray(key).add(first.itemId());
        rejects(() -> StockCatalog.parse(duplicate, names, known));
        var unknown = mapping.deepCopy();
        unknown.getAsJsonArray(key).set(0, new com.google.gson.JsonPrimitive("minecraft:not_real"));
        rejects(() -> StockCatalog.parse(unknown, names, known));
        var missingIndustry = mapping.deepCopy();
        missingIndustry.remove(key);
        rejects(() -> StockCatalog.parse(missingIndustry, names, known));
        var missingName = names.deepCopy();
        missingName.remove(first.itemId());
        rejects(() -> StockCatalog.parse(mapping, missingName, known));

        var bounds = new ArrayList<Integer>();
        Random zero =
                new Random(1) {
                    @Override
                    public int nextInt(int bound) {
                        bounds.add(bound);
                        return 0;
                    }
                };
        var choice = StockSettlement.selectCandidate(Set.of(), Set.of(), Set.of(), true, zero);
        check(
                bounds.equals(
                        List.of(9, StockCatalog.forIndustry(BuiltInIndustry.values()[0]).size())),
                "select industry first, then item; candidate count does not weight industry");
        check(choice.industry() == BuiltInIndustry.values()[0], "deterministic chosen industry");
        Set<BuiltInIndustry> covered = new HashSet<>(List.of(BuiltInIndustry.values()));
        covered.remove(first.industry());
        var excluded = new HashSet<String>();
        StockCatalog.forIndustry(first.industry())
                .forEach(candidate -> excluded.add(candidate.itemId()));
        choice = StockSettlement.selectCandidate(Set.of(), excluded, covered, false, zero);
        check(
                choice != null && choice.industry() == first.industry(),
                "missing industry can override recent exclusion");
        var occupied = new HashSet<>(excluded);
        check(
                StockSettlement.selectCandidate(occupied, Set.of(), covered, false, zero) == null,
                "no duplicate symbol when missing industry's pool is occupied");
        choice =
                StockSettlement.selectCandidate(
                        Set.of(first.itemId()), Set.of(), Set.of(), true, zero);
        check(!choice.itemId().equals(first.itemId()), "occupied symbol excluded");
        System.out.println(
                "STOCK_CATALOG_PASS: reviewed mappings, validation,"
                        + " industry-first random selection, coverage and occupied symbols");
    }

    private static void rejects(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Invalid stock mapping accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
