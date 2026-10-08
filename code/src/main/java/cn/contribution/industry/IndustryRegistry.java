package cn.contribution.industry;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class IndustryRegistry {
    private static final Map<BuiltInIndustry, IndustryTagSet> BUILT_INS =
            new EnumMap<>(BuiltInIndustry.class);

    private IndustryRegistry() {}

    public static void bootstrap() {
        if (!BUILT_INS.isEmpty()) {
            return;
        }

        for (BuiltInIndustry industry : BuiltInIndustry.values()) {
            BUILT_INS.put(industry, IndustryTags.forIndustry(industry));
        }
    }

    public static Map<BuiltInIndustry, IndustryTagSet> builtIns() {
        return Collections.unmodifiableMap(BUILT_INS);
    }
}
