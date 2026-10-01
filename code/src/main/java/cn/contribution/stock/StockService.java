package cn.contribution.stock;

import cn.contribution.ContributionMod;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.industry.BuiltInIndustry;
import cn.contribution.industry.RuleManager;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static cn.contribution.account.AccountService.uuidBytes;

/** Shared-DB stock market. No client classes, inventory items or cross-server caches. */
public final class StockService {
    private final DatabaseService database;
    private final String serverId;
    private final boolean mainServer;
    private boolean ticking;
    private int nextTick;

    public StockService(DatabaseService database, ServerConfig config) {
        this.database = database;
        this.serverId = config.serverId;
        this.mainServer = config.mainServer;
    }

    public void tick(MinecraftServer server) {
        if (!mainServer || ticking || server.getTickCount() < nextTick
                || database.state() != DatabaseState.AVAILABLE || !RuleManager.historyReady()) return;
        ticking = true;
        nextTick = server.getTickCount() + 20;
        long today = RuleManager.day(server);
        int time = (int) Math.floorMod(server.overworld().getOverworldClockTime(), 24000);
        database.transaction(connection -> {
            long storedDay;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT game_day FROM stock_market_state WHERE singleton_id = 1 FOR UPDATE");
                 ResultSet rows = query.executeQuery()) {
                rows.next(); storedDay = rows.getLong(1);
            }
            if (storedDay > today) throw new SQLException("Market clock is ahead of the main world");
            boolean transitioned = false;
            if (storedDay < today) {
                long target = storedDay < 0 ? today : storedDay + 1;
                boolean existingWorldFirstRun = storedDay < 0 && RuleManager.firstDay(connection) >= target;
                if (target >= 2 && !existingWorldFirstRun
                        && !StockSettlement.industryReady(connection, target - 1)) return 0;
                if (target >= 2) StockSettlement.run(connection, target);
                storedDay = target;
                transitioned = true;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE stock_market_state SET game_day = ?, day_time = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE singleton_id = 1")) {
                update.setLong(1, storedDay); update.setInt(2, storedDay == today ? time : 0);
                update.executeUpdate();
            }
            return transitioned ? 2 : 1;
        }).whenComplete((done, error) -> server.execute(() -> {
            ticking = false;
            if (error != null) {
                nextTick = server.getTickCount() + 600;
                ContributionMod.LOGGER.warn("Stock market daily transition is pending: {}", error.toString());
            } else if (done == 0) nextTick = server.getTickCount() + 100;
            else if (done == 2) server.getPlayerList().getPlayers().forEach(player ->
                    claim(player.getUUID(), UUID.randomUUID()));
        }));
    }

    public CompletableFuture<StockView.Market> market(UUID player) {
        return database.transaction(connection -> {
            long day; int time;
            try (PreparedStatement query = connection.prepareStatement("SELECT game_day, day_time FROM stock_market_state WHERE singleton_id = 1");
                 ResultSet rows = query.executeQuery()) {
                rows.next(); day = rows.getLong(1); time = rows.getInt(2);
            }
            List<StockView.Listing> listings = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT s.stock_id, s.item_id, s.item_name, s.industry_id, s.price, s.initial_price, s.status, "
                            + "COALESCE(p.quantity, 0) FROM stock_listing s LEFT JOIN stock_position p "
                            + "ON p.stock_id = s.stock_id AND p.player_uuid = ? WHERE s.status <> 'DELISTED' "
                            + "ORDER BY s.industry_id, s.item_name")) {
                query.setBytes(1, uuidBytes(player));
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) listings.add(listing(rows));
                }
            }
            return new StockView.Market(day, time, List.copyOf(listings));
        });
    }

    public CompletableFuture<StockView.Detail> detail(UUID player, String symbol, int days) {
        if (days != 7 && days != 30 && days != 360) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid chart range"));
        return database.transaction(connection -> {
            StockView.Listing listing = findListing(connection, player, symbol, false);
            if (listing == null) return null;
            List<StockView.PricePoint> prices = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT game_day, price FROM stock_daily_price WHERE stock_id = ? ORDER BY game_day DESC LIMIT ?")) {
                query.setLong(1, listing.id()); query.setInt(2, days);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) prices.add(new StockView.PricePoint(rows.getLong(1), rows.getInt(2)));
                }
            }
            java.util.Collections.reverse(prices);
            return new StockView.Detail(listing, List.copyOf(prices));
        });
    }

    public CompletableFuture<StockView.TradeResult> trade(UUID player, String symbol, int quantity,
                                                           boolean buy, UUID idempotency) {
        if (player == null || idempotency == null || symbol == null || symbol.isBlank()
                || symbol.length() > 128 || quantity < 1 || quantity > 10000)
            return CompletableFuture.completedFuture(fail("股票、数量或请求 ID 无效"));
        byte[] hash = hash(player + "|" + symbol + "|" + quantity + "|" + buy);
        return database.transaction(connection -> tradeLocked(connection, player, symbol, quantity, buy, idempotency, hash))
                .exceptionallyCompose(error -> database.transaction(connection -> {
                    StockView.TradeResult replay = replay(connection, idempotency, hash);
                    return replay == null ? fail("数据暂时不可用；请保留请求 ID 并重试") : replay;
                }).exceptionally(ignored -> fail("数据暂时不可用；请保留请求 ID 并重试")));
    }

    private StockView.TradeResult tradeLocked(Connection connection, UUID player, String symbol, int quantity,
                                              boolean buy, UUID requestId, byte[] hash) throws SQLException {
        StockView.TradeResult replay = replay(connection, requestId, hash);
        if (replay != null) return replay;
        if (accountRequestExists(connection, requestId)) return fail("请求 ID 已用于其他账户操作");
        long day; int time; boolean fresh;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT game_day, day_time, updated_at > CURRENT_TIMESTAMP(6) - INTERVAL '5' SECOND "
                        + "FROM stock_market_state WHERE singleton_id = 1 FOR UPDATE");
             ResultSet rows = query.executeQuery()) {
            rows.next(); day = rows.getLong(1); time = rows.getInt(2); fresh = rows.getBoolean(3);
        }
        if (!fresh) return fail("主服务器市场时钟暂不可用，交易已暂停");
        if (day < 2) return fail("股市尚未开放，将在第 3 个游戏日上市");
        if (time < 4000 || time >= 6000) return fail("仅在游戏时间 10:00—12:00 可交易");
        Account account = lockAccount(connection, player);
        if (account == null) return fail("未找到玩家账户，请重新进入服务器");
        StockView.Listing stock = findListing(connection, player, symbol, true);
        if (stock == null) return fail("未找到这支股票");
        if (buy && !stock.status().equals("ACTIVE")) return fail("退市保留期只允许卖出");
        if (!buy && stock.status().equals("RETIRING") && !retirementSaleAllowed(player, stock.id(), day))
            return fail("这支退市股票今天未能卖出（每日固定 30% 成功率）");
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM stock_trade WHERE player_uuid = ? AND stock_id = ? AND game_day = ? AND side = ?")) {
            query.setBytes(1, uuidBytes(player)); query.setLong(2, stock.id());
            query.setLong(3, day); query.setString(4, buy ? "BUY" : "SELL");
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) return fail("今天已对这支股票执行过一次" + (buy ? "买入" : "卖出"));
            }
        }
        Position position = lockPosition(connection, player, stock.id());
        int owned = position == null ? 0 : position.quantity;
        int boughtToday = position == null || position.boughtDay != day ? 0 : position.boughtQuantity;
        if (buy && owned + quantity > 10000) return fail("每支股票最多持有 10000 股");
        if (!buy && quantity > owned - boughtToday) return fail("可卖持仓不足；当日买入的股票当天不能卖出");
        long gross = (long) quantity * stock.price();
        long fee = (gross * 2 + 50) / 100;
        long delta = buy ? -gross - fee : gross - fee;
        if (delta < Integer.MIN_VALUE || delta > Integer.MAX_VALUE) return fail("交易金额超过单次变动范围");
        long nextBalance = account.balance + delta;
        long nextIncome = account.income + (buy ? 0 : delta);
        if (nextBalance < 0) return fail("余额不足，本次交易未发起");
        if (nextBalance > Integer.MAX_VALUE || nextIncome > Integer.MAX_VALUE) return fail("余额或历史总收入超过上限");
        if (buy && position == null) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO stock_position (player_uuid, stock_id, quantity, bought_day, bought_quantity) VALUES (?, ?, ?, ?, ?)")) {
                insert.setBytes(1, uuidBytes(player)); insert.setLong(2, stock.id());
                insert.setInt(3, quantity); insert.setLong(4, day); insert.setInt(5, quantity); insert.executeUpdate();
            }
        } else {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE stock_position SET quantity = ?, bought_day = ?, bought_quantity = ? WHERE player_uuid = ? AND stock_id = ?")) {
                update.setInt(1, owned + (buy ? quantity : -quantity));
                update.setLong(2, day);
                update.setInt(3, buy ? boughtToday + quantity : boughtToday);
                update.setBytes(4, uuidBytes(player)); update.setLong(5, stock.id()); update.executeUpdate();
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE contribution_account SET balance = ?, total_income = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setInt(1, (int) nextBalance); update.setInt(2, (int) nextIncome);
            update.setBytes(3, uuidBytes(player)); update.executeUpdate();
        }
        UUID tradeId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stock_trade (trade_id, idempotency_id, request_hash, player_uuid, stock_id, game_day, "
                        + "side, quantity, price, gross, fee, account_delta, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))")) {
            insert.setBytes(1, uuidBytes(tradeId)); insert.setBytes(2, uuidBytes(requestId));
            insert.setBytes(3, hash); insert.setBytes(4, uuidBytes(player)); insert.setLong(5, stock.id());
            insert.setLong(6, day); insert.setString(7, buy ? "BUY" : "SELL");
            insert.setInt(8, quantity); insert.setInt(9, stock.price()); insert.setLong(10, gross);
            insert.setLong(11, fee); insert.setInt(12, (int) delta); insert.executeUpdate();
        }
        accountTransaction(connection, tradeId, requestId, hash, player, account.name, (int) delta,
                buy ? 0 : (int) delta, account.balance, (int) nextBalance, buy ? "STOCK_BUY" : "STOCK_SELL",
                (buy ? "买入 " : "卖出 ") + stock.name() + " × " + quantity);
        return new StockView.TradeResult(true, buy ? "买入成功" : "卖出成功", stock.id(), quantity,
                stock.price(), fee, (int) nextBalance, false);
    }

    public CompletableFuture<String> claim(UUID player, UUID requestId) {
        return database.transaction(connection -> {
            try (PreparedStatement old = connection.prepareStatement(
                    "SELECT type, source FROM (SELECT * FROM contribution_transaction UNION ALL "
                            + "SELECT * FROM contribution_transaction_archive) transactions WHERE idempotency_id = ?")) {
                old.setBytes(1, uuidBytes(requestId));
                try (ResultSet row = old.executeQuery()) {
                    if (row.next()) return "REFUND".equals(row.getString(1))
                            && "contribution:stock".equals(row.getString(2))
                            ? "退市返还已领取；本次未重复发放" : "请求 ID 已用于其他账户操作";
                }
            }
            Account account = lockAccount(connection, player);
            if (account == null) return "账户未就绪";
            long room = Integer.MAX_VALUE - (long) account.balance;
            if (room == 0) return "余额已达上限；退市返还保留待领";
            long total = 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT stock_id, amount, claimed FROM stock_refund WHERE player_uuid = ? AND amount > claimed ORDER BY stock_id FOR UPDATE")) {
                query.setBytes(1, uuidBytes(player));
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next() && total < room) {
                        long part = Math.min(rows.getLong(2) - rows.getLong(3), room - total);
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE stock_refund SET claimed = claimed + ? WHERE player_uuid = ? AND stock_id = ?")) {
                            update.setLong(1, part); update.setBytes(2, uuidBytes(player));
                            update.setLong(3, rows.getLong(1)); update.executeUpdate();
                        }
                        total += part;
                    }
                }
            }
            if (total == 0) return "暂无待领取的退市返还";
            int paid = (int) total;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE contribution_account SET balance = balance + ?, updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
                update.setInt(1, paid); update.setBytes(2, uuidBytes(player)); update.executeUpdate();
            }
            accountTransaction(connection, UUID.randomUUID(), requestId, hash(player + "|refund"), player,
                    account.name, paid, 0, account.balance, account.balance + paid, "REFUND", "股票退市返还");
            return "已领取退市返还 " + paid + " 贡献值";
        }).exceptionally(error -> "返还暂时不可用，请稍后使用同一请求 ID 重试");
    }

    private static StockView.TradeResult replay(Connection connection, UUID requestId, byte[] hash) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT t.request_hash, t.stock_id, t.quantity, t.price, t.fee, t.player_uuid, "
                        + "a.balance FROM stock_trade t JOIN contribution_account a ON a.player_uuid = t.player_uuid "
                        + "WHERE t.idempotency_id = ?")) {
            query.setBytes(1, uuidBytes(requestId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                if (!MessageDigest.isEqual(hash, rows.getBytes(1))) return fail("请求 ID 已用于另一笔交易");
                return new StockView.TradeResult(true, "该交易此前已完成，未重复扣款", rows.getLong(2),
                        rows.getInt(3), rows.getInt(4), rows.getLong(5), rows.getInt(7), true);
            }
        }
    }

    private static boolean accountRequestExists(Connection connection, UUID requestId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM (SELECT * FROM contribution_transaction UNION ALL "
                        + "SELECT * FROM contribution_transaction_archive) transactions WHERE idempotency_id = ?")) {
            query.setBytes(1, uuidBytes(requestId));
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private static Account lockAccount(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_name, balance, total_income FROM contribution_account WHERE player_uuid = ? FOR UPDATE")) {
            query.setBytes(1, uuidBytes(player));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new Account(rows.getString(1), rows.getInt(2), rows.getInt(3)) : null;
            }
        }
    }

    private static Position lockPosition(Connection connection, UUID player, long stock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT quantity, bought_day, bought_quantity FROM stock_position WHERE player_uuid = ? AND stock_id = ? FOR UPDATE")) {
            query.setBytes(1, uuidBytes(player)); query.setLong(2, stock);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new Position(rows.getInt(1), rows.getLong(2), rows.getInt(3)) : null;
            }
        }
    }

    private static StockView.Listing findListing(Connection connection, UUID player, String symbol, boolean activeOnly) throws SQLException {
        String condition = symbol.matches("[0-9]{1,18}") ? "s.stock_id = ?" : "(s.item_id = ? OR s.item_name = ?)";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT s.stock_id, s.item_id, s.item_name, s.industry_id, s.price, s.initial_price, s.status, "
                        + "COALESCE(p.quantity, 0) FROM stock_listing s LEFT JOIN stock_position p "
                        + "ON p.stock_id = s.stock_id AND p.player_uuid = ? WHERE " + condition
                        + (activeOnly ? " AND s.status <> 'DELISTED'" : "")
                        + " ORDER BY s.stock_id DESC LIMIT 1")) {
            query.setBytes(1, uuidBytes(player));
            if (condition.equals("s.stock_id = ?")) query.setLong(2, Long.parseLong(symbol));
            else { query.setString(2, symbol.startsWith("minecraft:") ? symbol : "minecraft:" + symbol); query.setString(3, symbol); }
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? listing(rows) : null;
            }
        }
    }

    private static StockView.Listing listing(ResultSet rows) throws SQLException {
        String industry = rows.getString(4);
        for (BuiltInIndustry value : BuiltInIndustry.values())
            if (industry.equals("contribution:" + value.path())) industry = value.displayName();
        return new StockView.Listing(rows.getLong(1), rows.getString(2), rows.getString(3), industry,
                rows.getInt(5), rows.getInt(6), rows.getString(7), rows.getInt(8));
    }

    private void accountTransaction(Connection connection, UUID transaction, UUID requestId, byte[] hash,
                                    UUID player, String playerName, int delta, int incomeDelta,
                                    int before, int after, String type, String reason) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO contribution_transaction (transaction_id, idempotency_id, request_hash, player_uuid, "
                        + "player_name, amount, income_delta, balance_before, balance_after, type, source, reason, "
                        + "operator, server_id, created_at, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), NULL)")) {
            insert.setBytes(1, uuidBytes(transaction)); insert.setBytes(2, uuidBytes(requestId));
            insert.setBytes(3, hash); insert.setBytes(4, uuidBytes(player)); insert.setString(5, playerName);
            insert.setInt(6, delta); insert.setInt(7, incomeDelta); insert.setInt(8, before); insert.setInt(9, after);
            insert.setString(10, type); insert.setString(11, "contribution:stock");
            insert.setString(12, reason); insert.setString(13, "stock-market");
            insert.setString(14, serverId); insert.executeUpdate();
        }
    }

    private static boolean retirementSaleAllowed(UUID player, long stock, long day) {
        byte[] value = hash(player + "|" + stock + "|" + day + "|retirement");
        return (value[0] & 0xff) * 256 + (value[1] & 0xff) < 19661;
    }

    private static byte[] hash(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static StockView.TradeResult fail(String message) {
        return new StockView.TradeResult(false, message, 0, 0, 0, 0, 0, false);
    }

    private record Account(String name, int balance, int income) { }
    private record Position(int quantity, long boughtDay, int boughtQuantity) { }
}
