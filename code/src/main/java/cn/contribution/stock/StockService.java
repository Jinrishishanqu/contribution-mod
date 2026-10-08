package cn.contribution.stock;

import static cn.contribution.account.AccountService.uuidBytes;

import cn.contribution.ContributionMod;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.industry.BuiltInIndustry;
import cn.contribution.industry.RuleManager;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Shared-DB stock market. No client classes, inventory items or cross-server caches. */
public final class StockService {
    private final DatabaseService database;
    private final String serverId;
    private final boolean mainServer;
    private boolean ticking;
    private boolean coverageChecked;
    private boolean clockPublishing;
    private int nextClockTick;
    private int nextTick;
    private int nextPendingNoticeTick;
    private boolean replicaClockPolling;
    private int nextReplicaClockTick;
    private boolean noticePolling;
    private int nextNoticeTick;
    private final Random swanRandom = new Random();
    private final OnlinePlayerSnapshot onlinePlayers = new OnlinePlayerSnapshot();

    public StockService(DatabaseService database, ServerConfig config) {
        this.database = database;
        this.serverId = config.serverId;
        this.mainServer = config.mainServer;
    }

    public void tick(MinecraftServer server) {
        if (!mainServer) {
            tickReplicaClock(server);
            return;
        }
        publishClock(server);
        if (ticking
                || server.getTickCount() < nextTick
                || database.state() != DatabaseState.AVAILABLE
                || !RuleManager.historyReady()) return;
        ticking = true;
        nextTick = server.getTickCount() + 20;
        long clock = server.overworld().getOverworldClockTime();
        long today = RuleManager.day(server);
        int time = (int) Math.floorMod(clock, 24000);
        refreshOnlinePlayers(server);
        List<StockSwanService.Player> online = onlinePlayers.players();
        boolean repairCoverage = !coverageChecked;
        database.transaction(
                        connection -> {
                            long storedDay;
                            long closedDay;
                            try (PreparedStatement query =
                                            connection.prepareStatement(
                                                    "SELECT game_day, last_retirement_close_day,"
                                                        + " clock_server_id FROM stock_market_state"
                                                        + " WHERE singleton_id = 1 FOR UPDATE");
                                    ResultSet rows = query.executeQuery()) {
                                rows.next();
                                storedDay = rows.getLong(1);
                                closedDay = rows.getLong(2);
                                String owner = rows.getString(3);
                                if (owner != null && !owner.equals(serverId))
                                    throw new SQLException(
                                            "Market clock is owned by server_id="
                                                    + owner
                                                    + ", not "
                                                    + serverId);
                            }
                            if (storedDay > today)
                                throw new SQLException("Market clock is ahead of the main world");
                            StockSwanService.advance(connection, clock, online, swanRandom);
                            if (storedDay >= 0 && storedDay < today && closedDay < storedDay) {
                                StockSettlement.closeRetirements(connection, storedDay);
                                closedDay = storedDay;
                            }
                            boolean transitioned = false;
                            if (storedDay < today) {
                                long target = storedDay < 0 ? today : storedDay + 1;
                                boolean existingWorldFirstRun =
                                        storedDay < 0 && RuleManager.firstDay(connection) >= target;
                                if (target >= 2
                                        && !existingWorldFirstRun
                                        && !StockSettlement.industryReady(connection, target - 1))
                                    return new TickResult(
                                            0, StockSwanService.news(connection, clock));
                                if (target >= 2) {
                                    if (existingWorldFirstRun)
                                        StockSettlement.initialize(connection, target);
                                    else StockSettlement.run(connection, target);
                                }
                                storedDay = target;
                                transitioned = true;
                            }
                            if (repairCoverage && storedDay >= 2)
                                StockSettlement.initialize(connection, storedDay);
                            if (storedDay == today && time >= 8000 && closedDay < today) {
                                StockSettlement.closeRetirements(connection, today);
                                closedDay = today;
                            }
                            try (PreparedStatement update =
                                    connection.prepareStatement(
                                            "UPDATE stock_market_state SET game_day = ?, day_time ="
                                                + " ?, last_retirement_close_day = ?, updated_at ="
                                                + " CURRENT_TIMESTAMP(6) WHERE singleton_id = 1")) {
                                update.setLong(1, storedDay);
                                update.setInt(2, storedDay == today ? time : 0);
                                update.setLong(3, closedDay);
                                update.executeUpdate();
                            }
                            return new TickResult(
                                    transitioned ? 2 : 1, StockSwanService.news(connection, clock));
                        })
                .whenComplete(
                        (done, error) ->
                                server.execute(
                                        () -> {
                                            ticking = false;
                                            if (error != null) {
                                                nextTick = server.getTickCount() + 600;
                                                ContributionMod.LOGGER.warn(
                                                        "Stock market daily transition is pending:"
                                                                + " {}",
                                                        error.toString());
                                            } else if (done.state() == 0) {
                                                nextTick = server.getTickCount() + 100;
                                                if (server.getTickCount()
                                                        >= nextPendingNoticeTick) {
                                                    nextPendingNoticeTick =
                                                            server.getTickCount() + 1200;
                                                    ContributionMod.LOGGER.warn(
                                                            "Stock market day {} is waiting for"
                                                                + " industry_daily_settlement day"
                                                                + " {}. Check statistics_day_close"
                                                                + " for all configured"
                                                                + " statisticsServers.",
                                                            today,
                                                            today - 1);
                                                }
                                            } else {
                                                coverageChecked = true;
                                                if (done.state() == 2)
                                                    server.getPlayerList()
                                                            .getPlayers()
                                                            .forEach(
                                                                    player ->
                                                                            claim(
                                                                                    player
                                                                                            .getUUID(),
                                                                                    UUID
                                                                                            .randomUUID()));
                                            }
                                        }));
    }

    private record TickResult(int state, List<StockView.News> news) {}

    /** Heartbeats do not inherit settlement readiness, rollback or retry delays. */
    private void publishClock(MinecraftServer server) {
        if (clockPublishing
                || server.getTickCount() < nextClockTick
                || database.state() != DatabaseState.AVAILABLE) return;
        clockPublishing = true;
        nextClockTick = server.getTickCount() + 20;
        long clock = server.overworld().getOverworldClockTime();
        long day = Math.max(0, Math.floorDiv(clock, 24_000));
        int time = (int) Math.floorMod(clock, 24_000);
        long revision = StockUiNetwork.nextClockRevision();
        database.transaction(
                        connection -> {
                            StockMarketClock.publish(connection, serverId, clock);
                            return StockSwanService.news(connection, clock);
                        })
                .whenComplete(
                        (news, error) ->
                                server.execute(
                                        () -> {
                                            clockPublishing = false;
                                            if (error != null) {
                                                ContributionMod.LOGGER.warn(
                                                        "Stock market heartbeat failed: {}",
                                                        error.toString());
                                                return;
                                            }
                                            StockUiNetwork.broadcastClock(
                                                    server.getPlayerList().getPlayers(),
                                                    day,
                                                    time,
                                                    news,
                                                    revision);
                                        }));
    }

    /** One shared-clock read per secondary server, never one query per player. */
    private void tickReplicaClock(MinecraftServer server) {
        if (replicaClockPolling
                || server.getTickCount() < nextReplicaClockTick
                || database.state() != DatabaseState.AVAILABLE
                || server.getPlayerList().getPlayers().isEmpty()) return;
        replicaClockPolling = true;
        nextReplicaClockTick = server.getTickCount() + 20;
        long revision = StockUiNetwork.nextClockRevision();
        database.transaction(
                        connection -> {
                            try (PreparedStatement query =
                                            connection.prepareStatement(
                                                    "SELECT observed_day, observed_time,"
                                                        + " observed_at > CURRENT_TIMESTAMP(6) -"
                                                        + " INTERVAL '5' SECOND FROM"
                                                        + " stock_market_state WHERE singleton_id ="
                                                        + " 1");
                                    ResultSet rows = query.executeQuery()) {
                                rows.next();
                                long day = rows.getBoolean(3) ? rows.getLong(1) : -1;
                                int time = day < 0 ? -1 : rows.getInt(2);
                                return new ClockResult(
                                        day,
                                        time,
                                        day < 0
                                                ? List.of()
                                                : StockSwanService.news(
                                                        connection, day * 24_000 + time));
                            }
                        })
                .whenComplete(
                        (clock, error) ->
                                server.execute(
                                        () -> {
                                            replicaClockPolling = false;
                                            if (error == null)
                                                StockUiNetwork.broadcastClock(
                                                        server.getPlayerList().getPlayers(),
                                                        clock.day(),
                                                        clock.time(),
                                                        clock.news(),
                                                        revision);
                                        }));
    }

    private record ClockResult(long day, int time, List<StockView.News> news) {}

    public void invalidateOnlinePlayers() {
        onlinePlayers.invalidate();
    }

    private void refreshOnlinePlayers(MinecraftServer server) {
        int tick = server.getTickCount();
        if (!onlinePlayers.needsRefresh(tick)) return;
        onlinePlayers.replace(
                server.getPlayerList().getPlayers().stream()
                        .map(
                                player ->
                                        new StockSwanService.Player(
                                                player.getUUID(), player.getGameProfile().name()))
                        .toList(),
                tick);
    }

    public void requestRecovery() {
        nextTick = 0;
        nextClockTick = 0;
        nextReplicaClockTick = 0;
    }

    /**
     * One bounded off-thread notice query per server every ten seconds, including non-main servers.
     */
    public void tickNotices(MinecraftServer server) {
        if (noticePolling
                || server.getTickCount() < nextNoticeTick
                || database.state() != DatabaseState.AVAILABLE) return;
        nextNoticeTick = server.getTickCount() + 200;
        refreshOnlinePlayers(server);
        List<UUID> online = onlinePlayers.ids();
        if (online.isEmpty()) return;
        noticePolling = true;
        database.transaction(
                        connection -> {
                            String markers =
                                    String.join(
                                            ",", java.util.Collections.nCopies(online.size(), "?"));
                            List<Notice> notices = new ArrayList<>();
                            try (PreparedStatement query =
                                    connection.prepareStatement(
                                            "SELECT player_uuid, stock_id, game_day, kind, message"
                                                + " FROM stock_notice WHERE delivered_at IS NULL"
                                                + " AND player_uuid IN ("
                                                    + markers
                                                    + ") ORDER BY game_day, stock_id LIMIT 100 FOR"
                                                    + " UPDATE")) {
                                for (int i = 0; i < online.size(); i++)
                                    query.setBytes(i + 1, uuidBytes(online.get(i)));
                                try (ResultSet rows = query.executeQuery()) {
                                    while (rows.next())
                                        notices.add(
                                                new Notice(
                                                        cn.contribution.account.AccountService
                                                                .bytesUuid(rows.getBytes(1)),
                                                        rows.getLong(2),
                                                        rows.getLong(3),
                                                        rows.getString(4),
                                                        rows.getString(5)));
                                }
                            }
                            try (PreparedStatement update =
                                    connection.prepareStatement(
                                            "UPDATE stock_notice SET delivered_at ="
                                                + " CURRENT_TIMESTAMP(6) WHERE player_uuid = ? AND"
                                                + " stock_id = ? AND game_day = ? AND kind = ? AND"
                                                + " delivered_at IS NULL")) {
                                for (Notice notice : notices) {
                                    update.setBytes(1, uuidBytes(notice.player()));
                                    update.setLong(2, notice.stock());
                                    update.setLong(3, notice.day());
                                    update.setString(4, notice.kind());
                                    update.addBatch();
                                }
                                if (!notices.isEmpty()) update.executeBatch();
                            }
                            return notices;
                        })
                .whenComplete(
                        (notices, error) ->
                                server.execute(
                                        () -> {
                                            noticePolling = false;
                                            if (error != null) return;
                                            for (Notice notice : notices) {
                                                ServerPlayer player =
                                                        server.getPlayerList()
                                                                .getPlayer(notice.player());
                                                if (player != null)
                                                    player.sendSystemMessage(
                                                            Component.literal(notice.message()));
                                            }
                                        }));
    }

    private record Notice(UUID player, long stock, long day, String kind, String message) {}

    public CompletableFuture<StockView.Market> market(UUID player) {
        return database.transaction(connection -> loadMarket(connection, player));
    }

    private static StockView.Market loadMarket(Connection connection, UUID player)
            throws SQLException {
        long day;
        int time;
        long clockDay;
        int clockTime;
        boolean clockFresh;
        try (PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT game_day, day_time, observed_day, observed_time,"
                                    + " observed_at > CURRENT_TIMESTAMP(6) - INTERVAL '5' SECOND"
                                    + " FROM stock_market_state WHERE singleton_id = 1");
                ResultSet rows = query.executeQuery()) {
            rows.next();
            day = rows.getLong(1);
            time = rows.getInt(2);
            clockDay = rows.getLong(3);
            clockTime = rows.getInt(4);
            clockFresh = rows.getBoolean(5);
        }
        List<StockView.Listing> listings = new ArrayList<>();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT s.stock_id, s.item_id, s.item_name, s.industry_id, s.price,"
                            + " s.initial_price, s.status, COALESCE(p.quantity, 0), s.listed_day"
                            + " FROM stock_listing s LEFT JOIN stock_position p ON p.stock_id ="
                            + " s.stock_id AND p.player_uuid = ? WHERE s.status <> 'DELISTED' ORDER"
                            + " BY s.industry_id, s.item_name")) {
            query.setBytes(1, uuidBytes(player));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) listings.add(listing(rows));
            }
        }
        return new StockView.Market(
                day, time, List.copyOf(listings), clockDay, clockTime, clockFresh);
    }

    public CompletableFuture<StockView.Dashboard> dashboard(UUID player) {
        return database.transaction(
                connection -> {
                    StockView.Market market = loadMarket(connection, player);
                    java.util.Map<Long, List<StockView.PricePoint>> curves =
                            new java.util.HashMap<>();
                    java.util.Map<Long, StockView.PriceRange> ranges = new java.util.HashMap<>();
                    for (StockView.Listing listing : market.listings())
                        curves.put(listing.id(), new ArrayList<>());
                    try (PreparedStatement query =
                                    connection.prepareStatement(
                                            "SELECT stock_id, high_price, low_price FROM"
                                                    + " stock_listing WHERE status <> 'DELISTED'");
                            ResultSet rows = query.executeQuery()) {
                        while (rows.next())
                            ranges.put(
                                    rows.getLong(1),
                                    new StockView.PriceRange(rows.getInt(2), rows.getInt(3)));
                    }
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT p.stock_id, p.game_day, p.price FROM stock_daily_price"
                                        + " p JOIN stock_listing s ON s.stock_id = p.stock_id WHERE"
                                        + " s.status <> 'DELISTED' AND p.game_day >= ? ORDER BY"
                                        + " p.stock_id, p.game_day")) {
                        query.setLong(1, Math.max(0, market.day() - 29));
                        try (ResultSet rows = query.executeQuery()) {
                            while (rows.next()) {
                                List<StockView.PricePoint> points = curves.get(rows.getLong(1));
                                if (points != null)
                                    points.add(
                                            new StockView.PricePoint(
                                                    rows.getLong(2), rows.getInt(3)));
                            }
                        }
                    }
                    java.util.Map<Long, Integer> directions = new java.util.HashMap<>();
                    for (StockView.Listing listing : market.listings()) {
                        int direction = StockChart.lastMovement(curves.get(listing.id()));
                        if (direction == 0)
                            direction =
                                    lastMovementDirection(
                                            connection, listing.id(), listing.price());
                        directions.put(listing.id(), direction);
                    }
                    return new StockView.Dashboard(
                            market,
                            curves,
                            ranges,
                            portfolio(connection, player, market),
                            directions,
                            market.clockFresh()
                                    ? StockSwanService.news(
                                            connection,
                                            market.clockDay() * 24_000 + market.clockTime())
                                    : List.of());
                });
    }

    public CompletableFuture<StockView.Detail> detail(UUID player, String symbol, int days) {
        if (days != 7 && days != 30 && days != 360 && days != -1)
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid chart range"));
        return database.transaction(
                connection -> {
                    StockView.Listing listing = findListing(connection, player, symbol, false);
                    if (listing == null) return null;
                    StockView.PriceRange range;
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT high_price, low_price FROM stock_listing WHERE stock_id"
                                            + " = ?")) {
                        query.setLong(1, listing.id());
                        try (ResultSet row = query.executeQuery()) {
                            row.next();
                            range = new StockView.PriceRange(row.getInt(1), row.getInt(2));
                        }
                    }
                    List<StockView.PricePoint> prices = new ArrayList<>();
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT game_day, price FROM stock_daily_price WHERE stock_id ="
                                            + " ? ORDER BY game_day DESC LIMIT 361")) {
                        query.setLong(1, listing.id());
                        try (ResultSet rows = query.executeQuery()) {
                            while (rows.next())
                                prices.add(
                                        new StockView.PricePoint(rows.getLong(1), rows.getInt(2)));
                        }
                    }
                    java.util.Collections.reverse(prices);
                    java.util.Map<Integer, StockView.PriceTrend> trends = new java.util.HashMap<>();
                    for (int span : new int[] {7, 30, 360})
                        if (prices.size() > span) {
                            int old = prices.get(prices.size() - 1 - span).price();
                            int change = listing.price() - old;
                            trends.put(
                                    span,
                                    new StockView.PriceTrend(
                                            span, change, old == 0 ? 0 : change * 100.0 / old));
                        }
                    List<StockView.PricePoint> displayed =
                            days == -1
                                    ? allHistory(connection, listing.id())
                                    : List.copyOf(
                                            prices.subList(
                                                    Math.max(0, prices.size() - days),
                                                    prices.size()));
                    int direction = StockChart.lastMovement(prices);
                    if (direction == 0)
                        direction =
                                lastMovementDirection(connection, listing.id(), listing.price());
                    int previousPrice =
                            prices.size() >= 2
                                    ? prices.get(prices.size() - 2).price()
                                    : listing.price();
                    return new StockView.Detail(
                            listing,
                            displayed,
                            days,
                            range,
                            positionInfo(connection, player, listing.id()),
                            java.util.Map.copyOf(trends),
                            direction,
                            previousPrice);
                });
    }

    private static List<StockView.PricePoint> allHistory(Connection connection, long stockId)
            throws SQLException {
        int count;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM stock_daily_price WHERE stock_id = ?")) {
            query.setLong(1, stockId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                count = rows.getInt(1);
            }
        }
        StockChart.ExtremaSampler sample = new StockChart.ExtremaSampler(count, 512);
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT game_day, price FROM stock_daily_price WHERE stock_id = ? ORDER BY"
                                + " game_day")) {
            query.setLong(1, stockId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next())
                    sample.accept(new StockView.PricePoint(rows.getLong(1), rows.getInt(2)));
            }
        }
        return sample.finish();
    }

    private static int lastMovementDirection(Connection connection, long stockId, int price)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT price FROM stock_daily_price WHERE stock_id = ? AND price <> ? "
                                + "ORDER BY game_day DESC LIMIT 1")) {
            query.setLong(1, stockId);
            query.setInt(2, price);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Integer.compare(price, rows.getInt(1)) : 0;
            }
        }
    }

    private static StockView.Portfolio portfolio(
            Connection connection, UUID player, StockView.Market market) throws SQLException {
        java.util.Map<Long, StockView.PositionInfo> positions = loadBasis(connection, player, null);
        long value = 0;
        long cost = 0;
        for (StockView.Listing listing : market.listings()) {
            StockView.PositionInfo position = positions.get(listing.id());
            if (position == null || listing.owned() == 0) continue;
            value += (long) listing.price() * listing.owned();
            cost += position.costBasis();
        }
        int balance = 0;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT balance FROM contribution_account WHERE player_uuid = ?")) {
            query.setBytes(1, uuidBytes(player));
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) balance = rows.getInt(1);
            }
        }
        long realized =
                positions.values().stream().mapToLong(StockView.PositionInfo::realizedProfit).sum();
        return new StockView.Portfolio(balance, value, cost, value - cost, realized, positions);
    }

    private static StockView.PositionInfo positionInfo(
            Connection connection, UUID player, long stockId) throws SQLException {
        return loadBasis(connection, player, stockId)
                .getOrDefault(stockId, new StockView.PositionInfo(0, 0, -1, -1, 0, 0));
    }

    /** Weighted-average acquisition cost, including purchase fees; no main-thread history scan. */
    private static java.util.Map<Long, StockView.PositionInfo> loadBasis(
            Connection connection, UUID player, Long stockId) throws SQLException {
        java.util.Map<Long, Basis> basis = new java.util.HashMap<>();
        String sql =
                "SELECT t.stock_id, t.game_day, t.side, t.quantity, t.price, t.account_delta "
                        + "FROM stock_trade t WHERE t.player_uuid = ?"
                        + (stockId == null ? "" : " AND t.stock_id = ?")
                        + " ORDER BY t.stock_id, t.created_at, t.trade_id";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setBytes(1, uuidBytes(player));
            if (stockId != null) query.setLong(2, stockId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    long day = rows.getLong(2);
                    String side = rows.getString(3);
                    int quantity = rows.getInt(4);
                    Basis value = basis.computeIfAbsent(id, ignored -> new Basis());
                    if ("BUY".equals(side)) {
                        if (value.quantity == 0) value.firstBuyDay = day;
                        value.quantity += quantity;
                        value.costBasis -= rows.getInt(6);
                        value.lastBuyDay = day;
                        value.lastBuyPrice = rows.getInt(5);
                    } else if (value.quantity > 0) {
                        long removed =
                                Math.min(
                                        value.costBasis,
                                        Math.round(
                                                value.costBasis
                                                        * (quantity / (double) value.quantity)));
                        value.quantity -= quantity;
                        value.costBasis -= removed;
                        value.realizedProfit += rows.getInt(6) - removed;
                    }
                }
            }
        }
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT stock_id, amount FROM stock_refund WHERE player_uuid = ?"
                                + (stockId == null ? "" : " AND stock_id = ?"))) {
            query.setBytes(1, uuidBytes(player));
            if (stockId != null) query.setLong(2, stockId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Basis value = basis.get(rows.getLong(1));
                    if (value == null) continue;
                    value.realizedProfit += rows.getLong(2) - value.costBasis;
                    value.quantity = 0;
                    value.costBasis = 0;
                }
            }
        }
        java.util.Map<Long, StockView.PositionInfo> result = new java.util.HashMap<>();
        basis.forEach(
                (id, value) ->
                        result.put(
                                id,
                                new StockView.PositionInfo(
                                        value.quantity,
                                        value.costBasis,
                                        value.firstBuyDay,
                                        value.lastBuyDay,
                                        value.lastBuyPrice,
                                        value.realizedProfit)));
        return java.util.Map.copyOf(result);
    }

    private static final class Basis {
        int quantity;
        long costBasis;
        long firstBuyDay = -1;
        long lastBuyDay = -1;
        int lastBuyPrice;
        long realizedProfit;
    }

    public CompletableFuture<StockView.TradeResult> trade(
            UUID player, String symbol, int quantity, boolean buy, UUID idempotency) {
        if (player == null
                || idempotency == null
                || symbol == null
                || symbol.isBlank()
                || symbol.length() > 128
                || quantity < 1
                || quantity > 10000)
            return CompletableFuture.completedFuture(fail("股票、数量或请求 ID 无效"));
        byte[] hash = hash(player + "|" + symbol + "|" + quantity + "|" + buy);
        return database.transaction(
                        connection ->
                                tradeLocked(
                                        connection,
                                        player,
                                        symbol,
                                        quantity,
                                        buy,
                                        idempotency,
                                        hash))
                .exceptionallyCompose(
                        error ->
                                database.transaction(
                                                connection -> {
                                                    StockView.TradeResult replay =
                                                            replay(connection, idempotency, hash);
                                                    return replay == null
                                                            ? fail("数据暂时不可用；请保留请求 ID 并重试")
                                                            : replay;
                                                })
                                        .exceptionally(ignored -> fail("数据暂时不可用；请保留请求 ID 并重试")));
    }

    /**
     * Reserves a batch identity before any constituent trade begins. A changed retry is rejected.
     */
    public CompletableFuture<Boolean> openBatch(
            UUID player, UUID batchId, String symbols, int quantity, boolean buy) {
        byte[] requestHash = hash(player + "|" + symbols + "|" + quantity + "|" + buy);
        return database.transaction(
                connection -> {
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT player_uuid, request_hash FROM stock_batch_request"
                                            + " WHERE batch_id = ? FOR UPDATE")) {
                        query.setBytes(1, uuidBytes(batchId));
                        try (ResultSet rows = query.executeQuery()) {
                            if (rows.next())
                                return player.equals(
                                                cn.contribution.account.AccountService.bytesUuid(
                                                        rows.getBytes(1)))
                                        && MessageDigest.isEqual(requestHash, rows.getBytes(2));
                        }
                    }
                    try (PreparedStatement insert =
                            connection.prepareStatement(
                                    "INSERT INTO stock_batch_request (batch_id, player_uuid,"
                                            + " request_hash, created_at) VALUES (?, ?, ?,"
                                            + " CURRENT_TIMESTAMP(6))")) {
                        insert.setBytes(1, uuidBytes(batchId));
                        insert.setBytes(2, uuidBytes(player));
                        insert.setBytes(3, requestHash);
                        insert.executeUpdate();
                    }
                    return true;
                });
    }

    private StockView.TradeResult tradeLocked(
            Connection connection,
            UUID player,
            String symbol,
            int quantity,
            boolean buy,
            UUID requestId,
            byte[] hash)
            throws SQLException {
        StockView.TradeResult replay = replay(connection, requestId, hash);
        if (replay != null) return replay;
        if (accountRequestExists(connection, requestId)) return fail("请求 ID 已用于其他账户操作");
        long day;
        int time;
        long clockDay;
        boolean fresh;
        try (PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT game_day, observed_time, observed_day, observed_at >"
                                        + " CURRENT_TIMESTAMP(6) - INTERVAL '5' SECOND FROM"
                                        + " stock_market_state WHERE singleton_id = 1 FOR UPDATE");
                ResultSet rows = query.executeQuery()) {
            rows.next();
            day = rows.getLong(1);
            time = rows.getInt(2);
            clockDay = rows.getLong(3);
            fresh = rows.getBoolean(4);
        }
        if (!fresh) return fail("主服务器市场时钟暂不可用，交易已暂停");
        long accountingDay = time < 2000 ? clockDay - 1 : clockDay;
        if (day != accountingDay) return fail("当前游戏日的行业与股价核算尚未完成，交易已暂停");
        if (day < 2) return fail("股市尚未开放，将在第 3 个游戏日上市");
        if (time < 4000 || time >= 8000) return fail("仅在游戏时间 10:00—14:00 可交易");
        Account account = lockAccount(connection, player);
        if (account == null) return fail("未找到玩家账户，请重新进入服务器");
        StockView.Listing stock = findListing(connection, player, symbol, true);
        if (stock == null) return fail("未找到这支股票");
        if (buy && !stock.status().equals("ACTIVE")) return fail("退市当日只允许卖出");
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT 1 FROM stock_trade WHERE player_uuid = ? AND stock_id = ? AND"
                                + " game_day = ? AND side = ?")) {
            query.setBytes(1, uuidBytes(player));
            query.setLong(2, stock.id());
            query.setLong(3, day);
            query.setString(4, buy ? "BUY" : "SELL");
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) return fail("今天已对这支股票执行过一次" + (buy ? "买入" : "卖出"));
            }
        }
        Position position = lockPosition(connection, player, stock.id());
        int owned = position == null ? 0 : position.quantity;
        int boughtToday =
                position == null || position.boughtDay != day ? 0 : position.boughtQuantity;
        if (buy && owned + quantity > 10000) return fail("每支股票最多持有 10000 股");
        if (!buy && quantity > owned - boughtToday) return fail("可卖持仓不足；当日买入的股票当天不能卖出");
        long gross = (long) quantity * stock.price();
        long fee = (gross * 2 + 50) / 100;
        long delta = buy ? -gross - fee : gross - fee;
        if (delta < Integer.MIN_VALUE || delta > Integer.MAX_VALUE) return fail("交易金额超过单次变动范围");
        long nextBalance = account.balance + delta;
        long nextIncome = account.income;
        if (nextBalance < 0) return fail("余额不足，本次交易未发起");
        if (nextBalance > Integer.MAX_VALUE || nextIncome > Integer.MAX_VALUE)
            return fail("余额或历史总收入超过上限");
        if (buy && position == null) {
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO stock_position (player_uuid, stock_id, quantity,"
                                    + " bought_day, bought_quantity) VALUES (?, ?, ?, ?, ?)")) {
                insert.setBytes(1, uuidBytes(player));
                insert.setLong(2, stock.id());
                insert.setInt(3, quantity);
                insert.setLong(4, day);
                insert.setInt(5, quantity);
                insert.executeUpdate();
            }
        } else {
            try (PreparedStatement update =
                    connection.prepareStatement(
                            "UPDATE stock_position SET quantity = ?, bought_day = ?,"
                                + " bought_quantity = ? WHERE player_uuid = ? AND stock_id = ?")) {
                update.setInt(1, owned + (buy ? quantity : -quantity));
                update.setLong(2, day);
                update.setInt(3, buy ? boughtToday + quantity : boughtToday);
                update.setBytes(4, uuidBytes(player));
                update.setLong(5, stock.id());
                update.executeUpdate();
            }
        }
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE contribution_account SET balance = ?, total_income = ?, updated_at"
                                + " = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setInt(1, (int) nextBalance);
            update.setInt(2, (int) nextIncome);
            update.setBytes(3, uuidBytes(player));
            update.executeUpdate();
        }
        UUID tradeId = UUID.randomUUID();
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO stock_trade (trade_id, idempotency_id, request_hash,"
                            + " player_uuid, stock_id, game_day, side, quantity, price, gross, fee,"
                            + " account_delta, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, CURRENT_TIMESTAMP(6))")) {
            insert.setBytes(1, uuidBytes(tradeId));
            insert.setBytes(2, uuidBytes(requestId));
            insert.setBytes(3, hash);
            insert.setBytes(4, uuidBytes(player));
            insert.setLong(5, stock.id());
            insert.setLong(6, day);
            insert.setString(7, buy ? "BUY" : "SELL");
            insert.setInt(8, quantity);
            insert.setInt(9, stock.price());
            insert.setLong(10, gross);
            insert.setLong(11, fee);
            insert.setInt(12, (int) delta);
            insert.executeUpdate();
        }
        accountTransaction(
                connection,
                tradeId,
                requestId,
                hash,
                player,
                account.name,
                (int) delta,
                0,
                account.balance,
                (int) nextBalance,
                buy ? "SPEND" : "STOCK",
                (buy ? "买入 " : "卖出 ") + stock.name() + " × " + quantity);
        return new StockView.TradeResult(
                true,
                buy ? "买入成功" : "卖出成功",
                stock.id(),
                quantity,
                stock.price(),
                fee,
                (int) nextBalance,
                false);
    }

    public CompletableFuture<String> claim(UUID player, UUID requestId) {
        return database.transaction(
                        connection -> {
                            try (PreparedStatement old =
                                    connection.prepareStatement(
                                            "SELECT type, source FROM (SELECT * FROM"
                                                    + " contribution_transaction UNION ALL SELECT *"
                                                    + " FROM contribution_transaction_archive)"
                                                    + " transactions WHERE idempotency_id = ?")) {
                                old.setBytes(1, uuidBytes(requestId));
                                try (ResultSet row = old.executeQuery()) {
                                    if (row.next())
                                        return "REFUND".equals(row.getString(1))
                                                        && "contribution:stock"
                                                                .equals(row.getString(2))
                                                ? "退市返还已领取；本次未重复发放"
                                                : "请求 ID 已用于其他账户操作";
                                }
                            }
                            Account account = lockAccount(connection, player);
                            if (account == null) return "账户未就绪";
                            long room = Integer.MAX_VALUE - (long) account.balance;
                            if (room == 0) return "余额已达上限；退市返还保留待领";
                            long total = 0;
                            try (PreparedStatement query =
                                    connection.prepareStatement(
                                            "SELECT stock_id, amount, claimed FROM stock_refund"
                                                    + " WHERE player_uuid = ? AND amount > claimed"
                                                    + " ORDER BY stock_id FOR UPDATE")) {
                                query.setBytes(1, uuidBytes(player));
                                try (ResultSet rows = query.executeQuery()) {
                                    while (rows.next() && total < room) {
                                        long part =
                                                Math.min(
                                                        rows.getLong(2) - rows.getLong(3),
                                                        room - total);
                                        try (PreparedStatement update =
                                                connection.prepareStatement(
                                                        "UPDATE stock_refund SET claimed = claimed"
                                                                + " + ? WHERE player_uuid = ? AND"
                                                                + " stock_id = ?")) {
                                            update.setLong(1, part);
                                            update.setBytes(2, uuidBytes(player));
                                            update.setLong(3, rows.getLong(1));
                                            update.executeUpdate();
                                        }
                                        total += part;
                                    }
                                }
                            }
                            if (total == 0) return "暂无待领取的退市返还";
                            int paid = (int) total;
                            try (PreparedStatement update =
                                    connection.prepareStatement(
                                            "UPDATE contribution_account SET balance = balance + ?,"
                                                    + " updated_at = CURRENT_TIMESTAMP(6) WHERE"
                                                    + " player_uuid = ?")) {
                                update.setInt(1, paid);
                                update.setBytes(2, uuidBytes(player));
                                update.executeUpdate();
                            }
                            accountTransaction(
                                    connection,
                                    UUID.randomUUID(),
                                    requestId,
                                    hash(player + "|refund"),
                                    player,
                                    account.name,
                                    paid,
                                    0,
                                    account.balance,
                                    account.balance + paid,
                                    "REFUND",
                                    "股票退市返还");
                            return "已领取退市返还 " + paid + " 贡献值";
                        })
                .exceptionally(error -> "返还暂时不可用，请稍后使用同一请求 ID 重试");
    }

    private static StockView.TradeResult replay(Connection connection, UUID requestId, byte[] hash)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT t.request_hash, t.stock_id, t.quantity, t.price, t.fee,"
                                + " t.player_uuid, a.balance FROM stock_trade t JOIN"
                                + " contribution_account a ON a.player_uuid = t.player_uuid WHERE"
                                + " t.idempotency_id = ?")) {
            query.setBytes(1, uuidBytes(requestId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                if (!MessageDigest.isEqual(hash, rows.getBytes(1))) return fail("请求 ID 已用于另一笔交易");
                return new StockView.TradeResult(
                        true,
                        "该交易此前已完成，未重复扣款",
                        rows.getLong(2),
                        rows.getInt(3),
                        rows.getInt(4),
                        rows.getLong(5),
                        rows.getInt(7),
                        true);
            }
        }
    }

    private static boolean accountRequestExists(Connection connection, UUID requestId)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT 1 FROM (SELECT * FROM contribution_transaction UNION ALL SELECT *"
                                + " FROM contribution_transaction_archive) transactions WHERE"
                                + " idempotency_id = ?")) {
            query.setBytes(1, uuidBytes(requestId));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static Account lockAccount(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT player_name, balance, total_income FROM contribution_account WHERE"
                                + " player_uuid = ? FOR UPDATE")) {
            query.setBytes(1, uuidBytes(player));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next()
                        ? new Account(rows.getString(1), rows.getInt(2), rows.getInt(3))
                        : null;
            }
        }
    }

    private static Position lockPosition(Connection connection, UUID player, long stock)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT quantity, bought_day, bought_quantity FROM stock_position WHERE"
                                + " player_uuid = ? AND stock_id = ? FOR UPDATE")) {
            query.setBytes(1, uuidBytes(player));
            query.setLong(2, stock);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next()
                        ? new Position(rows.getInt(1), rows.getLong(2), rows.getInt(3))
                        : null;
            }
        }
    }

    private static StockView.Listing findListing(
            Connection connection, UUID player, String symbol, boolean activeOnly)
            throws SQLException {
        String condition =
                symbol.matches("[0-9]{1,18}")
                        ? "s.stock_id = ?"
                        : "(s.item_id = ? OR s.item_name = ?)";
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT s.stock_id, s.item_id, s.item_name, s.industry_id, s.price,"
                            + " s.initial_price, s.status, COALESCE(p.quantity, 0), s.listed_day"
                            + " FROM stock_listing s LEFT JOIN stock_position p ON p.stock_id ="
                            + " s.stock_id AND p.player_uuid = ? WHERE "
                                + condition
                                + (activeOnly ? " AND s.status <> 'DELISTED'" : "")
                                + " ORDER BY s.stock_id DESC LIMIT 1")) {
            query.setBytes(1, uuidBytes(player));
            if (condition.equals("s.stock_id = ?")) query.setLong(2, Long.parseLong(symbol));
            else {
                query.setString(
                        2, symbol.startsWith("minecraft:") ? symbol : "minecraft:" + symbol);
                query.setString(3, symbol);
            }
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? listing(rows) : null;
            }
        }
    }

    private static StockView.Listing listing(ResultSet rows) throws SQLException {
        String industry = rows.getString(4);
        for (BuiltInIndustry value : BuiltInIndustry.values())
            if (industry.equals("contribution:" + value.path())) industry = value.displayName();
        return new StockView.Listing(
                rows.getLong(1),
                rows.getString(2),
                rows.getString(3),
                industry,
                rows.getInt(5),
                rows.getInt(6),
                rows.getString(7),
                rows.getInt(8),
                rows.getLong(9));
    }

    private void accountTransaction(
            Connection connection,
            UUID transaction,
            UUID requestId,
            byte[] hash,
            UUID player,
            String playerName,
            int delta,
            int incomeDelta,
            int before,
            int after,
            String type,
            String reason)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO contribution_transaction (transaction_id, idempotency_id,"
                            + " request_hash, player_uuid, player_name, amount, income_delta,"
                            + " balance_before, balance_after, type, source, reason, operator,"
                            + " server_id, created_at, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?, CURRENT_TIMESTAMP(6), NULL)")) {
            insert.setBytes(1, uuidBytes(transaction));
            insert.setBytes(2, uuidBytes(requestId));
            insert.setBytes(3, hash);
            insert.setBytes(4, uuidBytes(player));
            insert.setString(5, playerName);
            insert.setInt(6, delta);
            insert.setInt(7, incomeDelta);
            insert.setInt(8, before);
            insert.setInt(9, after);
            insert.setString(10, type);
            insert.setString(11, "contribution:stock");
            insert.setString(12, reason);
            insert.setString(13, "stock-market");
            insert.setString(14, serverId);
            insert.executeUpdate();
        }
    }

    private static byte[] hash(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static StockView.TradeResult fail(String message) {
        return new StockView.TradeResult(false, message, 0, 0, 0, 0, 0, false);
    }

    private record Account(String name, int balance, int income) {}

    private record Position(int quantity, long boughtDay, int boughtQuantity) {}
}
