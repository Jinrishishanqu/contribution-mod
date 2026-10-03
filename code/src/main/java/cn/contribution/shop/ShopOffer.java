package cn.contribution.shop;

/** Shared catalog entry; itemSpec is the validated vanilla command item including component patches. */
public record ShopOffer(long id, String name, String itemId, int itemCount, int price,
                        String description, boolean listed, int sortOrder, long revision, String itemSpec) {
    public ShopOffer(long id, String name, String itemId, int itemCount, int price,
                     String description, boolean listed, int sortOrder, long revision) {
        this(id, name, itemId, itemCount, price, description, listed, sortOrder, revision, itemId);
    }
    public String itemSpec() { return itemSpec == null || itemSpec.isBlank() ? itemId : itemSpec; }
}
