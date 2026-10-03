package cn.contribution.shop;

import cn.contribution.config.RewardConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Shared authoritative metadata, independent of UI and purchase accounting. */
public final class ShopCatalog {
    public static final int MAX_PRODUCTS = 256;
    private ShopCatalog() { }
    public record Change(String name, String itemId, Integer itemCount, Integer price,
                         String description, Boolean listed, Integer sortOrder, String itemSpec) {
        public Change(String name, String itemId, Integer itemCount, Integer price,
                      String description, Boolean listed, Integer sortOrder) {
            this(name, itemId, itemCount, price, description, listed, sortOrder, null);
        }
    }
    public record Result(boolean success, String message, long id) { }

    public static void seed(Connection connection, RewardConfig.ShopOffer[] defaults) throws SQLException {
        try (var query = connection.prepareStatement("SELECT seeded FROM shop_catalog_state WHERE singleton_id = 1 FOR UPDATE");
             var row = query.executeQuery()) {
            if (!row.next()) throw new SQLException("Shop catalog state is missing");
            if (row.getBoolean(1)) return;
        }
        try (var insert = connection.prepareStatement(
                "INSERT INTO shop_offer (legacy_id, name, item_id, item_count, price, description, listed, sort_order, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, '', TRUE, ?, CURRENT_TIMESTAMP(6))")) {
            for (int index = 0; index < defaults.length; index++) {
                var offer = defaults[index];
                insert.setString(1, offer.id); insert.setString(2, offer.name); insert.setString(3, offer.itemId);
                insert.setInt(4, offer.itemCount); insert.setInt(5, offer.price); insert.setInt(6, index); insert.addBatch();
            }
            if (defaults.length > 0) insert.executeBatch();
        }
        try (var update = connection.prepareStatement(
                "UPDATE shop_catalog_state SET seeded = TRUE, updated_at = CURRENT_TIMESTAMP(6) WHERE singleton_id = 1")) {
            update.executeUpdate();
        }
    }

    public static List<ShopOffer> list(Connection connection, boolean includeUnlisted) throws SQLException {
        requireReady(connection);
        List<ShopOffer> result = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT offer_id, name, item_id, item_count, price, description, listed, sort_order, revision, item_spec "
                + "FROM shop_offer " + (includeUnlisted ? "" : "WHERE listed = TRUE ") + "ORDER BY sort_order, offer_id LIMIT " + MAX_PRODUCTS);
             var rows = query.executeQuery()) {
            while (rows.next()) result.add(read(rows));
        }
        return List.copyOf(result);
    }

    public static ShopOffer find(Connection connection, String identifier, boolean lock) throws SQLException {
        Long numeric = number(identifier);
        try (var query = connection.prepareStatement(
                "SELECT offer_id, name, item_id, item_count, price, description, listed, sort_order, revision, item_spec FROM shop_offer WHERE "
                        + (numeric == null ? "legacy_id = ?" : "offer_id = ?") + (lock ? " FOR UPDATE" : ""))) {
            if (numeric == null) query.setString(1, identifier); else query.setLong(1, numeric);
            try (var rows = query.executeQuery()) { return rows.next() ? read(rows) : null; }
        }
    }

    public static Result create(Connection connection, Change change) throws SQLException {
        String invalid = validate(change);
        if (invalid != null) return new Result(false, invalid, 0);
        try (var lock = connection.prepareStatement("SELECT seeded FROM shop_catalog_state WHERE singleton_id = 1 FOR UPDATE");
             var state = lock.executeQuery()) {
            if (!state.next() || !state.getBoolean(1)) throw new SQLException("Shop catalog is not ready");
        }
        try (var count = connection.prepareStatement("SELECT COUNT(*) FROM shop_offer"); var row = count.executeQuery()) {
            row.next();
            if (row.getInt(1) >= MAX_PRODUCTS) return new Result(false, "商品数量已达到 " + MAX_PRODUCTS + " 种", 0);
        }
        try (var insert = connection.prepareStatement(
                "INSERT INTO shop_offer (name, item_id, item_count, price, description, listed, sort_order, item_spec, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))", Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, change); insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Shop product ID was not generated");
                long id = keys.getLong(1);
                return new Result(true, "已创建商品 #" + id + (change.listed() ? "，已上架" : "，未上架"), id);
            }
        }
    }

    public static Result modify(Connection connection, long id, Change patch, Long expectedRevision) throws SQLException {
        ShopOffer old = find(connection, Long.toString(id), true);
        if (old == null) return new Result(false, "商品 #" + id + " 不存在", id);
        if (expectedRevision != null && old.revision() != expectedRevision)
            return new Result(false, "商品已被其他管理员修改，请刷新后重新编辑", id);
        Change updated = new Change(patch.name() == null ? old.name() : patch.name(),
                patch.itemId() == null ? old.itemId() : patch.itemId(),
                patch.itemCount() == null ? old.itemCount() : patch.itemCount(),
                patch.price() == null ? old.price() : patch.price(),
                patch.description() == null ? old.description() : patch.description(),
                patch.listed() == null ? old.listed() : patch.listed(),
                patch.sortOrder() == null ? old.sortOrder() : patch.sortOrder(),
                patch.itemSpec() != null ? patch.itemSpec() : patch.itemId() == null ? old.itemSpec() : patch.itemId());
        String invalid = validate(updated);
        if (invalid != null) return new Result(false, invalid, id);
        try (var update = connection.prepareStatement("UPDATE shop_offer SET name = ?, item_id = ?, item_count = ?, price = ?, "
                + "description = ?, listed = ?, sort_order = ?, item_spec = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP(6) WHERE offer_id = ?")) {
            bind(update, updated); update.setLong(9, id); update.executeUpdate();
        }
        return new Result(true, "已更新商品 #" + id + (updated.listed() ? "，已上架" : "，已下架"), id);
    }

    private static String validate(Change change) {
        if (change.name() == null || change.name().isBlank() || length(change.name()) > 64) return "商品名称需要 1—64 个字符";
        if (change.itemId() == null || change.itemId().length() > 128
                || !change.itemId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return "物品 ID 无效";
        if (change.itemSpec() != null && change.itemSpec().length() > 2048) return "物品组件定义最多 2048 个字符";
        if (change.itemSpec() != null && !change.itemSpec().equals(change.itemId())
                && !change.itemSpec().startsWith(change.itemId() + "[")) return "物品定义与物品 ID 不一致";
        if (change.itemCount() == null || change.itemCount() < 1 || change.itemCount() > 64) return "每份数量需要 1—64 个";
        if (change.price() == null || change.price() < 1) return "售价需要 1—2147483647 贡献值";
        if (change.description() == null || length(change.description()) > 512) return "描述最多 512 个字符";
        if (change.listed() == null || change.sortOrder() == null) return "商品状态或排序无效";
        return null;
    }
    private static int length(String value) { return value.codePointCount(0, value.length()); }
    private static void bind(PreparedStatement statement, Change change) throws SQLException {
        statement.setString(1, change.name()); statement.setString(2, change.itemId());
        statement.setInt(3, change.itemCount()); statement.setInt(4, change.price());
        statement.setString(5, change.description()); statement.setBoolean(6, change.listed()); statement.setInt(7, change.sortOrder());
        statement.setString(8, change.itemSpec() == null ? change.itemId() : change.itemSpec());
    }
    private static ShopOffer read(ResultSet rows) throws SQLException {
        return new ShopOffer(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getInt(4), rows.getInt(5),
                rows.getString(6), rows.getBoolean(7), rows.getInt(8), rows.getLong(9), rows.getString(10));
    }
    private static void requireReady(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT seeded FROM shop_catalog_state WHERE singleton_id = 1"); var rows = query.executeQuery()) {
            if (!rows.next() || !rows.getBoolean(1)) throw new SQLException("Shop catalog is waiting for the main server");
        }
    }
    private static Long number(String value) {
        try { long id = Long.parseLong(value); return id > 0 ? id : null; }
        catch (NumberFormatException invalid) { return null; }
    }
}
