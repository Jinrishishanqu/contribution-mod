package cn.contribution.industry;

public enum BuiltInIndustry {
    CONSTRUCTION_LANDSCAPING("construction_landscaping", "土建园林"),
    MINING_METALLURGY("mining_metallurgy", "采矿冶金"),
    ENERGY_CHEMICAL("energy_chemical", "能源化工"),
    PROCESSING_MANUFACTURING("processing_manufacturing", "加工制造"),
    TECHNOLOGY_MAGIC("technology_magic", "科技魔法"),
    AGRICULTURE_FORESTRY_LIVESTOCK_FISHERY("agriculture_forestry_livestock_fishery", "农林牧渔"),
    MILITARY_FOOD_MEDICINE("military_food_medicine", "军工食药"),
    CIRCULATION_SERVICES("circulation_services", "流通服务"),
    CULTURE_EDUCATION_LIVELIHOOD("culture_education_livelihood", "文教民生");

    private final String path;
    private final String displayName;

    BuiltInIndustry(String path, String displayName) {
        this.path = path;
        this.displayName = displayName;
    }

    public String path() {
        return path;
    }

    public String displayName() {
        return displayName;
    }
}
