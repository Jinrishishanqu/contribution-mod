package cn.contribution.shop;

/** Immutable shared catalog entry; numeric ID is never reused after delisting. */
public record ShopOffer(long id, String name, String itemId, int itemCount, int price,
                        String description, boolean listed, int sortOrder, long revision) { }
