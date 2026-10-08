package cn.contribution.industry;

import cn.contribution.ContributionMod;
import cn.contribution.account.AccountService;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class StatisticsService {
    private static final int MAX_BUFFER_KEYS = 50_000;
    private static final long MAX_JOURNAL_BYTES = 64L * 1024 * 1024;
    private volatile boolean journalFull;
    private static volatile byte[] activeRuleHash = computeBuiltInRuleHash();
    private final DatabaseService database;
    private final ServerConfig config;
    private GameEventRules eventRules;
    private byte[] configHash;
    private final Map<IndustryDayKey, Long> industry = new HashMap<>();
    private final Map<PlayerKey, PlayerCounts> player = new HashMap<>();
    private final Map<PlayerIndustryKey, Long> playerIndustry = new HashMap<>();
    private final CounterBuffer<DistanceDayKey> distance = new CounterBuffer<>();
    private final CounterBuffer<MeasuredStatistics.Key> measured = new CounterBuffer<>();
    private final ExpiringDedup<DedupKey> recentBlocks = new ExpiringDedup<>(MAX_BUFFER_KEYS, 20);
    private final List<Batch> pending = new ArrayList<>();
    private final Set<UUID> journaled = new HashSet<>();
    private final ExecutorService journalExecutor =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "contribution-statistics-journal");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Path journalDirectory;
    private int lastFlushTick;
    private int pendingKeyCount;
    private long nextBatchSequence = 1;
    private boolean flushing;
    private boolean journaling;
    private boolean closingDay;
    private long lastClosedDay = -1;
    private int nextCloseRetryTick;
    private int nextCapacityWarning;
    private long nextPendingErrorNotice;

    public StatisticsService(DatabaseService database, ServerConfig config) {
        this(
                database,
                config,
                FabricLoader.getInstance()
                        .getConfigDir()
                        .resolve("contribution/statistics-journal"),
                RuleManager.current());
    }

    public StatisticsService(DatabaseService database, ServerConfig config, Path journalDirectory) {
        this(database, config, journalDirectory, RuleManager.current());
    }

    StatisticsService(
            DatabaseService database,
            ServerConfig config,
            Path journalDirectory,
            RuleSnapshot snapshot) {
        this.database = database;
        this.config = config;
        this.configHash = snapshot.hash();
        activeRuleHash = configHash.clone();
        this.eventRules = new GameEventRules(snapshot.events);
        this.journalDirectory = journalDirectory;
        try {
            Files.createDirectories(journalDirectory);
            try (var paths = Files.list(journalDirectory)) {
                long size = 0;
                for (Path path : paths.filter(value -> value.toString().endsWith(".bin")).toList())
                    size += Files.size(path);
                if (size > MAX_JOURNAL_BYTES)
                    throw new IOException(
                            "Recovery logs exceed 64 MiB; preserve and inspect them before"
                                    + " restart");
            }
            try (var files = Files.list(journalDirectory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".bin"))
                        .sorted(
                                Comparator.comparingLong(StatisticsService::journalModifiedTime)
                                        .thenComparing(Path::toString))
                        .forEach(
                                path -> {
                                    try {
                                        Batch batch = readJournal(path);
                                        if (batch.sequence > 0) {
                                            nextBatchSequence =
                                                    Math.max(nextBatchSequence, batch.sequence + 1);
                                        }
                                        pending.add(batch);
                                        pendingKeyCount += batch.keyCount();
                                        if (pendingKeyCount > MAX_BUFFER_KEYS)
                                            throw new IOException(
                                                    "Recovery entries exceed memory safety limit");
                                        journaled.add(batch.id);
                                    } catch (IOException error) {
                                        throw new IllegalStateException(
                                                "Invalid statistics recovery log: " + path, error);
                                    }
                                });
                pending.sort(Comparator.comparingLong(Batch::sequence));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot open statistics recovery journal", error);
        }
    }

    public void block(
            ServerPlayer actor, BlockPos pos, BlockState state, IndustryMatcher.Action action) {
        if (!collecting(actor.level().getServer())) {
            return;
        }
        PlayerKey who = new PlayerKey(actor.getUUID(), actor.getGameProfile().name());
        if (!hasCapacity(actor.level().getServer(), player.containsKey(who) ? 0 : 1)) {
            return;
        }
        PlayerCounts counts = player.computeIfAbsent(who, unused -> new PlayerCounts());
        if (action == IndustryMatcher.Action.PLACE) {
            counts.placed = StatisticMath.add(counts.placed, 1);
        } else if (action == IndustryMatcher.Action.MINE) {
            counts.mined = StatisticMath.add(counts.mined, 1);
        }
        if (!eligible(actor)) {
            return;
        }
        int tick = actor.level().getServer().getTickCount();
        DedupKey key =
                new DedupKey(
                        config.serverId,
                        actor.level().dimension().identifier().toString(),
                        who.uuid,
                        action,
                        pos.asLong(),
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!recentBlocks.accept(key, tick)) return;
        IndustryMatcher.match(action, null, state).ifPresent(found -> add(actor, found, 1));
    }

    public void craft(ServerPlayer actor, ItemStack produced) {
        if (!collecting(actor.level().getServer()) || !eligible(actor) || produced.isEmpty()) {
            return;
        }
        IndustryMatcher.match(IndustryMatcher.Action.CRAFT, produced, null)
                .ifPresent(
                        found -> {
                            String event = "contribution:craft/" + found.path();
                            // Until the next rule epoch, preserve the old day's published
                            // semantics.
                            if (eventRules.rule(event) == null)
                                add(actor, found, produced.getCount());
                            else
                                gameEvent(
                                        actor.level().getServer(),
                                        event,
                                        produced.getCount(),
                                        actor);
                        });
    }

    public void usedItem(ServerPlayer actor, ItemStack before, long quantity) {
        if (!collecting(actor.level().getServer()) || !eligible(actor) || quantity <= 0) return;
        IndustryMatcher.match(IndustryMatcher.Action.USE, before, null)
                .ifPresent(found -> add(actor, found, quantity));
    }

    public void interacted(ServerPlayer actor, BlockPos pos, BlockState before) {
        if (!collecting(actor.level().getServer()) || !eligible(actor)) return;
        int tick = actor.level().getServer().getTickCount();
        DedupKey key =
                new DedupKey(
                        config.serverId,
                        actor.level().dimension().identifier().toString(),
                        actor.getUUID(),
                        IndustryMatcher.Action.INTERACT,
                        pos.asLong(),
                        BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString());
        if (!recentBlocks.accept(key, tick)) return;
        IndustryMatcher.match(IndustryMatcher.Action.INTERACT, null, before)
                .ifPresent(found -> add(actor, found, 1));
    }

    public void gameEvent(
            MinecraftServer server, String eventId, long quantity, ServerPlayer actor) {
        if (!collecting(server) || quantity <= 0 || (actor != null && !eligible(actor))) {
            return;
        }
        GameEventRules.Rule rule = eventRules.rule(eventId);
        if (rule == null) {
            // Upgraded adapters can run while yesterday's immutable epoch is still active.
            if (GameEventRules.loadBuiltIn().rule(eventId) != null) return;
            throw new IllegalArgumentException("Unknown game event ID: " + eventId);
        }
        var key =
                new MeasuredStatistics.Key(
                        RuleManager.day(server),
                        actor == null ? MeasuredStatistics.WORLD : actor.getUUID(),
                        eventId);
        if (!hasCapacity(server, measured.containsKey(key) ? 0 : 1)) return;
        measured.add(key, quantity);
    }

    /**
     * The sampler reports measured movement; conversion and remainder are committed with the
     * statistics batch.
     */
    public void distance(ServerPlayer actor, String mode, long microblocks) {
        if (!collecting(actor.level().getServer()) || !eligible(actor) || microblocks <= 0) {
            return;
        }
        if (eventRules.rule("contribution:logistics/distance/" + mode) == null) return;
        long day = RuleManager.day(actor.level().getServer());
        DistanceDayKey key = new DistanceDayKey(day, actor.getUUID(), mode);
        if (!hasCapacity(actor.level().getServer(), distance.containsKey(key) ? 0 : 1)) return;
        distance.add(key, microblocks);
    }

    private void add(ServerPlayer actor, BuiltInIndustry found, long amount) {
        add(actor.level().getServer(), actor, found, amount);
    }

    private void add(
            MinecraftServer server, ServerPlayer actor, BuiltInIndustry found, long amount) {
        long day = RuleManager.day(server);
        int required = industry.containsKey(new IndustryDayKey(day, found.path())) ? 0 : 1;
        if (actor != null
                && !playerIndustry.containsKey(
                        new PlayerIndustryKey(actor.getUUID(), found.path()))) required++;
        if (!hasCapacity(server, required)) return;
        industry.merge(new IndustryDayKey(day, found.path()), amount, StatisticMath::add);
        if (actor != null) {
            playerIndustry.merge(
                    new PlayerIndustryKey(actor.getUUID(), found.path()),
                    amount,
                    StatisticMath::add);
        }
    }

    public void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick % 20 == 0) recentBlocks.expire(tick);
        if (tick - lastFlushTick >= 100
                || industry.size()
                                + player.size()
                                + playerIndustry.size()
                                + distance.size()
                                + measured.size()
                        >= 500) {
            lastFlushTick = tick;
            flush(server);
        }
        closeCompletedDays(server);
    }

    private void closeCompletedDays(MinecraftServer server) {
        if (!enabled()
                || !RuleManager.historyReady()
                || closingDay
                || server.getTickCount() < nextCloseRetryTick) {
            return;
        }
        long today = RuleManager.day(server);
        if (today <= 0
                || server.getTickCount() % 20 != 0
                || lastClosedDay >= today - 1
                || (config.mainServer && !RuleManager.settlementWindow(server))
                || !isDrainedThrough(today - 1)) {
            return;
        }
        long latestComplete = today - 1;
        closingDay = true;
        database.transaction(connection -> markClosedDays(connection, latestComplete))
                .whenComplete(
                        (closed, error) ->
                                server.execute(
                                        () -> {
                                            closingDay = false;
                                            if (error == null) {
                                                lastClosedDay = Math.max(lastClosedDay, closed);
                                            } else {
                                                nextCloseRetryTick = server.getTickCount() + 600;
                                                ContributionMod.LOGGER.warn(
                                                        "Statistics day close is pending for"
                                                                + " server_id={}: {}",
                                                        config.serverId,
                                                        error.toString());
                                            }
                                        }));
    }

    long markClosedDays(Connection connection, long latestComplete) throws SQLException {
        long nextDay = RuleManager.firstDay(connection);
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT MAX(game_day) FROM statistics_day_close WHERE server_id = ?")) {
            query.setString(1, config.serverId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                long previous = rows.getLong(1);
                if (!rows.wasNull()) {
                    nextDay = previous + 1;
                }
            }
        }
        if (nextDay > latestComplete) {
            return latestComplete;
        }
        long through = Math.min(latestComplete, nextDay + 31);
        try (PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT IGNORE INTO statistics_day_close (server_id, game_day,"
                                        + " config_hash, closed_at) VALUES (?, ?, ?,"
                                        + " CURRENT_TIMESTAMP(6))");
                PreparedStatement check =
                        connection.prepareStatement(
                                "SELECT config_hash FROM statistics_day_close WHERE server_id = ?"
                                        + " AND game_day = ?")) {
            for (long day = nextDay; day <= through; day++) {
                byte[] dayHash = RuleManager.dayHash(connection, day, configHash);
                insert.setString(1, config.serverId);
                insert.setLong(2, day);
                insert.setBytes(3, dayHash);
                insert.executeUpdate();
                check.setString(1, config.serverId);
                check.setLong(2, day);
                try (ResultSet rows = check.executeQuery()) {
                    if (!rows.next() || !MessageDigest.isEqual(dayHash, rows.getBytes(1))) {
                        throw new SQLException(
                                "Statistics day close config mismatch: "
                                        + config.serverId
                                        + "/"
                                        + day);
                    }
                }
            }
        }
        return through;
    }

    public void flush(MinecraftServer server) {
        if (!enabled() || !RuleManager.historyReady() || flushing || journaling) {
            return;
        }
        if (!industry.isEmpty()
                || !player.isEmpty()
                || !playerIndustry.isEmpty()
                || !distance.isEmpty()
                || !measured.isEmpty()) {
            Batch batch =
                    new Batch(
                            UUID.randomUUID(),
                            nextBatchSequence++,
                            configHash.clone(),
                            new HashMap<>(industry),
                            copyPlayers(player),
                            new HashMap<>(playerIndustry),
                            distance.snapshot(),
                            measured.snapshot());
            pending.add(batch);
            pendingKeyCount += batch.keyCount();
            industry.clear();
            player.clear();
            playerIndustry.clear();
            distance.clear();
            measured.clear();
        }
        pumpPending(server);
    }

    public void requestRecovery(MinecraftServer server) {
        nextCloseRetryTick = 0;
        flush(server);
    }

    /** Continue durable backlog without collecting fresh buffers on every database callback. */
    private void pumpPending(MinecraftServer server) {
        if (flushing || journaling || pending.isEmpty()) {
            return;
        }
        Batch batch = pending.getFirst();
        if (!journaled.contains(batch.id)) {
            journaling = true;
            CompletableFuture.runAsync(
                            () -> {
                                try {
                                    writeJournal(batch);
                                } catch (IOException error) {
                                    throw new IllegalStateException(error);
                                }
                            },
                            journalExecutor)
                    .whenComplete(
                            (unused, error) ->
                                    server.execute(
                                            () -> {
                                                journaling = false;
                                                if (error == null) {
                                                    journaled.add(batch.id);
                                                    pumpPending(server);
                                                } else {
                                                    ContributionMod.LOGGER.warn(
                                                            "Statistics journal write failed: {}",
                                                            error.toString());
                                                }
                                            }));
            return;
        }
        flushing = true;
        database.transaction(
                        connection -> {
                            writeBatch(connection, batch);
                            return null;
                        })
                .whenComplete(
                        (unused, error) ->
                                server.execute(
                                        () -> {
                                            flushing = false;
                                            if (error == null) {
                                                pending.remove(batch);
                                                pendingKeyCount -= batch.keyCount();
                                                journaled.remove(batch.id);
                                                CompletableFuture.runAsync(
                                                        () -> {
                                                            try {
                                                                Files.deleteIfExists(
                                                                        journalPath(batch.id));
                                                            } catch (IOException deletionError) {
                                                                ContributionMod.LOGGER.warn(
                                                                        "Cannot remove applied"
                                                                            + " statistics journal"
                                                                            + " {}",
                                                                        batch.id,
                                                                        deletionError);
                                                            }
                                                        },
                                                        journalExecutor);
                                                pumpPending(server);
                                            } else if (System.nanoTime()
                                                    >= nextPendingErrorNotice) {
                                                nextPendingErrorNotice =
                                                        System.nanoTime()
                                                                + TimeUnit.MINUTES.toNanos(1);
                                                ContributionMod.LOGGER.warn(
                                                        "STATISTICS_BATCH_FAILED batch={}"
                                                            + " sequence={} pendingKeys={} (journal"
                                                            + " retained)",
                                                        batch.id,
                                                        batch.sequence,
                                                        pendingKeyCount,
                                                        error);
                                            }
                                        }));
    }

    public boolean isDrained() {
        return !flushing
                && !journaling
                && pending.isEmpty()
                && industry.isEmpty()
                && player.isEmpty()
                && playerIndustry.isEmpty()
                && distance.isEmpty()
                && measured.isEmpty();
    }

    /**
     * Only dated industry/distance entries can change a closed day's industry result. In-flight
     * batches stay in pending until the commit callback, so historical writes remain a barrier.
     * Undated player totals do not participate in industry settlement. Called only on the server
     * thread; rule changes still require the full isDrained barrier.
     */
    public boolean isDrainedThrough(long day) {
        if (industry.keySet().stream().anyMatch(key -> key.day() <= day)
                || distance.keySet().stream().anyMatch(key -> key.day() <= day)
                || measured.keySet().stream().anyMatch(key -> key.day() <= day)) return false;
        return pending.stream().noneMatch(batch -> batch.affectsThrough(day));
    }

    public String diagnosticState() {
        return "enabled="
                + enabled()
                + " pendingBatches="
                + pending.size()
                + " pendingKeys="
                + pendingKeyCount
                + " bufferedKeys="
                + (industry.size()
                        + distance.size()
                        + measured.size()
                        + player.size()
                        + playerIndustry.size())
                + " flushing="
                + flushing
                + " journaling="
                + journaling
                + " journalFull="
                + journalFull
                + " lastClosedDay="
                + lastClosedDay;
    }

    public void close() {
        journalExecutor.shutdown();
        try {
            if (!journalExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                journalExecutor.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (!industry.isEmpty()
                || !player.isEmpty()
                || !playerIndustry.isEmpty()
                || !distance.isEmpty()
                || !measured.isEmpty()) {
            pending.add(
                    new Batch(
                            UUID.randomUUID(),
                            nextBatchSequence++,
                            configHash.clone(),
                            new HashMap<>(industry),
                            copyPlayers(player),
                            new HashMap<>(playerIndustry),
                            distance.snapshot(),
                            measured.snapshot()));
        }
        for (Batch batch : pending) {
            if (!journaled.contains(batch.id)) {
                try {
                    writeJournal(batch);
                } catch (IOException error) {
                    ContributionMod.LOGGER.error(
                            "Cannot persist pending statistics batch {}", batch.id, error);
                }
            }
        }
    }

    private int bufferedKeys() {
        return industry.size()
                + player.size()
                + playerIndustry.size()
                + distance.size()
                + measured.size()
                + pendingKeyCount;
    }

    private boolean hasCapacity(MinecraftServer server, int additional) {
        if (bufferedKeys() + additional <= MAX_BUFFER_KEYS) return true;
        if (server.getTickCount() >= nextCapacityWarning) {
            nextCapacityWarning = server.getTickCount() + 1200;
            ContributionMod.LOGGER.warn(
                    "Statistics buffer reached 50000 keys; new keys are paused until recovery");
        }
        return false;
    }

    private static long journalModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot order statistics journals: " + path, error);
        }
    }

    private Path journalPath(UUID id) {
        return journalDirectory.resolve(id + ".bin");
    }

    private void writeJournal(Batch batch) throws IOException {
        Path temporary = journalDirectory.resolve(batch.id + ".tmp");
        try (FileChannel channel =
                        FileChannel.open(
                                temporary,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE);
                DataOutputStream output =
                        new DataOutputStream(
                                new BufferedOutputStream(Channels.newOutputStream(channel)))) {
            output.writeInt(0x43535535);
            output.writeLong(batch.id.getMostSignificantBits());
            output.writeLong(batch.id.getLeastSignificantBits());
            output.writeLong(batch.sequence);
            output.write(batch.configHash);
            output.writeInt(batch.industry.size());
            for (var entry : batch.industry.entrySet()) {
                output.writeLong(entry.getKey().day);
                output.writeUTF(entry.getKey().industry);
                output.writeLong(entry.getValue());
            }
            output.writeInt(batch.player.size());
            for (var entry : batch.player.entrySet()) {
                writeUuid(output, entry.getKey().uuid);
                output.writeUTF(entry.getKey().name);
                output.writeLong(entry.getValue().placed);
                output.writeLong(entry.getValue().mined);
            }
            output.writeInt(batch.playerIndustry.size());
            for (var entry : batch.playerIndustry.entrySet()) {
                writeUuid(output, entry.getKey().uuid);
                output.writeUTF(entry.getKey().industry);
                output.writeLong(entry.getValue());
            }
            output.writeInt(batch.distance.size());
            for (var entry : batch.distance.entrySet()) {
                output.writeLong(entry.getKey().day);
                writeUuid(output, entry.getKey().uuid);
                output.writeUTF(entry.getKey().mode);
                output.writeLong(entry.getValue());
            }
            output.writeInt(batch.measured.size());
            for (var entry : batch.measured.entrySet()) {
                output.writeLong(entry.getKey().day());
                writeUuid(output, entry.getKey().actor());
                output.writeUTF(entry.getKey().event());
                output.writeLong(entry.getValue());
            }
            output.flush();
            channel.force(true);
        }
        try {
            long bytes = Files.size(temporary);
            try (var files = Files.list(journalDirectory)) {
                for (Path path : files.filter(value -> value.toString().endsWith(".bin")).toList())
                    bytes += Files.size(path);
            }
            if (bytes > MAX_JOURNAL_BYTES) {
                journalFull = true;
                Files.deleteIfExists(temporary);
                throw new IOException("Statistics recovery log reached 64 MiB; collection paused");
            }
            Files.move(
                    temporary,
                    journalPath(batch.id),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            journalFull = false;
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, journalPath(batch.id), StandardCopyOption.REPLACE_EXISTING);
            journalFull = false;
        }
    }

    private static Batch readJournal(Path path) throws IOException {
        try (DataInputStream input =
                new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int format = input.readInt();
            if (format != 0x43535532
                    && format != 0x43535533
                    && format != 0x43535534
                    && format != 0x43535535) {
                throw new IOException("Unsupported statistics journal version");
            }
            UUID id = new UUID(input.readLong(), input.readLong());
            long sequence = format >= 0x43535534 ? input.readLong() : 0;
            if (sequence < 0) {
                throw new IOException("Invalid statistics journal sequence");
            }
            byte[] configHash = input.readNBytes(32);
            if (configHash.length != 32) {
                throw new IOException("Incomplete statistics journal config hash");
            }
            Map<IndustryDayKey, Long> industry = new HashMap<>();
            int industryCount = boundedCount(input.readInt());
            for (int index = 0; index < industryCount; index++) {
                industry.put(
                        new IndustryDayKey(input.readLong(), input.readUTF()), input.readLong());
            }
            Map<PlayerKey, PlayerCounts> player = new HashMap<>();
            int playerCount = boundedCount(input.readInt());
            for (int index = 0; index < playerCount; index++) {
                PlayerKey key = new PlayerKey(readUuid(input), input.readUTF());
                PlayerCounts counts = new PlayerCounts();
                counts.placed = input.readLong();
                counts.mined = input.readLong();
                player.put(key, counts);
            }
            Map<PlayerIndustryKey, Long> playerIndustry = new HashMap<>();
            int playerIndustryCount = boundedCount(input.readInt());
            for (int index = 0; index < playerIndustryCount; index++) {
                playerIndustry.put(
                        new PlayerIndustryKey(readUuid(input), input.readUTF()), input.readLong());
            }
            Map<DistanceDayKey, Long> distance = new HashMap<>();
            if (format >= 0x43535533) {
                int distanceCount = boundedCount(input.readInt());
                for (int index = 0; index < distanceCount; index++) {
                    distance.put(
                            new DistanceDayKey(input.readLong(), readUuid(input), input.readUTF()),
                            input.readLong());
                }
            }
            Map<MeasuredStatistics.Key, Long> measured = new HashMap<>();
            if (format >= 0x43535535) {
                int count = boundedCount(input.readInt());
                for (int i = 0; i < count; i++)
                    measured.put(
                            new MeasuredStatistics.Key(
                                    input.readLong(), readUuid(input), input.readUTF()),
                            input.readLong());
            }
            return new Batch(
                    id, sequence, configHash, industry, player, playerIndustry, distance, measured);
        }
    }

    private static int boundedCount(int count) throws IOException {
        if (count < 0 || count > MAX_BUFFER_KEYS) {
            throw new IOException("Invalid statistics journal entry count: " + count);
        }
        return count;
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    void writeBatch(Connection connection, Batch batch) throws SQLException {
        byte[] payloadHash = sha256(batch.canonical().getBytes(StandardCharsets.UTF_8));
        long gameDay =
                Math.max(
                        batch.industry.keySet().stream()
                                .mapToLong(IndustryDayKey::day)
                                .max()
                                .orElse(0),
                        batch.distance.keySet().stream()
                                .mapToLong(DistanceDayKey::day)
                                .max()
                                .orElse(0));
        gameDay =
                Math.max(
                        gameDay,
                        batch.measured.keySet().stream()
                                .mapToLong(MeasuredStatistics.Key::day)
                                .max()
                                .orElse(0));
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT IGNORE INTO statistics_batch (batch_id, server_id, game_day,"
                            + " config_version, config_hash, payload_hash, created_at, applied_at)"
                            + " VALUES (?, ?, ?, 1, ?, ?, CURRENT_TIMESTAMP(6),"
                            + " CURRENT_TIMESTAMP(6))")) {
            insert.setBytes(1, AccountService.uuidBytes(batch.id));
            insert.setString(2, config.serverId);
            insert.setLong(3, gameDay);
            insert.setBytes(4, batch.configHash);
            insert.setBytes(5, payloadHash);
            if (insert.executeUpdate() == 0) {
                try (PreparedStatement check =
                        connection.prepareStatement(
                                "SELECT payload_hash, config_hash FROM statistics_batch WHERE"
                                        + " batch_id = ?")) {
                    check.setBytes(1, AccountService.uuidBytes(batch.id));
                    try (ResultSet rows = check.executeQuery()) {
                        if (!rows.next()
                                || !MessageDigest.isEqual(payloadHash, rows.getBytes(1))
                                || !MessageDigest.isEqual(batch.configHash, rows.getBytes(2))) {
                            throw new SQLException("Statistics batch idempotency conflict");
                        }
                    }
                }
                return;
            }
        }
        Map<IndustryDayKey, Long> industryDeltas = new HashMap<>(batch.industry);
        Map<PlayerIndustryKey, Long> playerIndustryDeltas = new HashMap<>(batch.playerIndustry);
        RuleSnapshot snapshot = RuleManager.snapshot(batch.configHash);
        if (snapshot == null
                && !Arrays.equals(configHash, batch.configHash)
                && (!batch.distance.isEmpty() || !batch.measured.isEmpty()))
            throw new SQLException(
                    "Historical distance rule snapshot is missing; recovery batch retained");
        GameEventRules batchRules =
                snapshot == null && Arrays.equals(configHash, batch.configHash)
                        ? eventRules
                        : snapshot == null
                                ? GameEventRules.loadBuiltIn()
                                : new GameEventRules(snapshot.events);
        convertDistance(
                connection, batch.distance, industryDeltas, playerIndustryDeltas, batchRules);
        MeasuredStatistics.apply(
                connection,
                config.serverId,
                batch.measured,
                batchRules,
                industryDeltas,
                playerIndustryDeltas);
        List<Map.Entry<IndustryDayKey, Long>> industryEntries =
                new ArrayList<>(industryDeltas.entrySet());
        industryEntries.sort(
                Comparator.comparingLong(
                                (Map.Entry<IndustryDayKey, Long> entry) -> entry.getKey().day)
                        .thenComparing(entry -> entry.getKey().industry));
        try (PreparedStatement create =
                        connection.prepareStatement(
                                "INSERT IGNORE INTO industry_day_accumulator (game_day,"
                                    + " industry_id, development, config_version, config_hash,"
                                    + " updated_at) VALUES (?, ?, 0, 1, ?, CURRENT_TIMESTAMP(6))");
                PreparedStatement check =
                        connection.prepareStatement(
                                "SELECT config_hash FROM industry_day_accumulator "
                                        + "WHERE game_day = ? AND industry_id = ? FOR UPDATE");
                PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE industry_day_accumulator SET development ="
                                        + " LEAST(9223372036854775807, CAST(development AS"
                                        + " DECIMAL(30,0)) + ?), updated_at = CURRENT_TIMESTAMP(6)"
                                        + " WHERE game_day = ? AND industry_id = ?")) {
            for (var entry : industryEntries) {
                long day = entry.getKey().day;
                String industryId = "contribution:" + entry.getKey().industry;
                create.setLong(1, day);
                create.setString(2, industryId);
                create.setBytes(3, batch.configHash);
                create.executeUpdate();
                check.setLong(1, day);
                check.setString(2, industryId);
                try (ResultSet rows = check.executeQuery()) {
                    if (!rows.next()
                            || !MessageDigest.isEqual(batch.configHash, rows.getBytes(1))) {
                        throw new SQLException(
                                "Industry config hash mismatch for "
                                        + industryId
                                        + " on day "
                                        + day);
                    }
                }
                update.setLong(1, entry.getValue());
                update.setLong(2, day);
                update.setString(3, industryId);
                update.executeUpdate();
            }
        }
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO player_activity_stats (player_uuid, player_name,"
                            + " player_name_normalized, total_placed, total_mined, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY"
                            + " UPDATE total_placed = LEAST(9223372036854775807, CAST(total_placed"
                            + " AS DECIMAL(30,0)) + VALUES(total_placed)), total_mined ="
                            + " LEAST(9223372036854775807, CAST(total_mined AS DECIMAL(30,0)) +"
                            + " VALUES(total_mined)), updated_at = CURRENT_TIMESTAMP(6)")) {
            for (var entry : batch.player.entrySet()) {
                statement.setBytes(1, AccountService.uuidBytes(entry.getKey().uuid));
                statement.setString(2, entry.getKey().name);
                statement.setString(3, entry.getKey().name.toLowerCase(Locale.ROOT));
                statement.setLong(4, entry.getValue().placed);
                statement.setLong(5, entry.getValue().mined);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        var changedPlayers =
                playerIndustryDeltas.keySet().stream()
                        .map(key -> key.uuid)
                        .distinct()
                        .sorted()
                        .toList();
        PlayerDevelopmentTotals.lock(connection, changedPlayers);
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO player_industry_stats (player_uuid, industry_id, development,"
                            + " updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY"
                            + " UPDATE development = LEAST(9223372036854775807, CAST(development AS"
                            + " DECIMAL(30,0)) + VALUES(development)), updated_at ="
                            + " CURRENT_TIMESTAMP(6)")) {
            for (var entry : playerIndustryDeltas.entrySet()) {
                statement.setBytes(1, AccountService.uuidBytes(entry.getKey().uuid));
                statement.setString(2, "contribution:" + entry.getKey().industry);
                statement.setLong(3, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        PlayerDevelopmentTotals.refresh(connection, changedPlayers);
    }

    private void convertDistance(
            Connection connection,
            Map<DistanceDayKey, Long> raw,
            Map<IndustryDayKey, Long> industryDeltas,
            Map<PlayerIndustryKey, Long> playerIndustryDeltas,
            GameEventRules batchRules)
            throws SQLException {
        if (raw.isEmpty()) {
            return;
        }
        List<Map.Entry<DistanceDayKey, Long>> entries = new ArrayList<>(raw.entrySet());
        entries.sort(
                Comparator.comparingLong(
                                (Map.Entry<DistanceDayKey, Long> entry) -> entry.getKey().day)
                        .thenComparing(entry -> entry.getKey().uuid.toString())
                        .thenComparing(entry -> entry.getKey().mode));
        try (PreparedStatement create =
                        connection.prepareStatement(
                                "INSERT IGNORE INTO player_distance_remainder (player_uuid,"
                                    + " movement_type, remainder_micro, updated_at) VALUES (?, ?,"
                                    + " 0, CURRENT_TIMESTAMP(6))");
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT remainder_micro FROM player_distance_remainder "
                                        + "WHERE player_uuid = ? AND movement_type = ? FOR UPDATE");
                PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE player_distance_remainder SET remainder_micro = ?,"
                                    + " updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ? AND"
                                    + " movement_type = ?")) {
            for (var entry : entries) {
                DistanceDayKey key = entry.getKey();
                GameEventRules.Rule rule =
                        batchRules.rule("contribution:logistics/distance/" + key.mode);
                if (rule == null
                        || !"distance_block".equals(rule.measure())
                        || entry.getValue() <= 0) {
                    throw new SQLException("Invalid distance batch event: " + key.mode);
                }
                long threshold = Math.multiplyExact(rule.unitSize(), 1_000_000L);
                byte[] playerId = AccountService.uuidBytes(key.uuid);
                create.setBytes(1, playerId);
                create.setString(2, key.mode);
                create.executeUpdate();
                query.setBytes(1, playerId);
                query.setString(2, key.mode);
                long remainder;
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("Missing distance remainder row");
                    }
                    remainder = rows.getLong(1);
                }
                DistanceAccumulator.Result result;
                try {
                    result = DistanceAccumulator.add(remainder, entry.getValue(), threshold);
                } catch (IllegalArgumentException error) {
                    throw new SQLException("Invalid distance remainder for " + key.mode, error);
                }
                update.setLong(1, result.remainder());
                update.setBytes(2, playerId);
                update.setString(3, key.mode);
                update.executeUpdate();
                if (result.completeUnits() > 0) {
                    long points = StatisticMath.multiply(result.completeUnits(), rule.unitValue());
                    industryDeltas.merge(
                            new IndustryDayKey(key.day, rule.industry().path()),
                            points,
                            StatisticMath::add);
                    playerIndustryDeltas.merge(
                            new PlayerIndustryKey(key.uuid, rule.industry().path()),
                            points,
                            StatisticMath::add);
                }
            }
        }
    }

    public record PlayerSummary(long placed, long mined, Map<String, Long> industries) {
        public long development(BuiltInIndustry industry) {
            return industries.getOrDefault("contribution:" + industry.path(), 0L);
        }

        public String description() {
            StringBuilder text =
                    new StringBuilder("放置：").append(placed).append("，挖掘：").append(mined);
            for (BuiltInIndustry industry : BuiltInIndustry.values())
                text.append("；")
                        .append(industry.displayName())
                        .append("：")
                        .append(development(industry));
            return text.toString();
        }
    }

    public CompletableFuture<String> playerSummary(UUID uuid) {
        return playerSummaryData(uuid).thenApply(PlayerSummary::description);
    }

    public CompletableFuture<PlayerSummary> playerSummaryData(UUID uuid) {
        return database.transaction(
                connection -> {
                    long placed = 0;
                    long mined = 0;
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT total_placed, total_mined FROM player_activity_stats"
                                            + " WHERE player_uuid = ?")) {
                        statement.setBytes(1, AccountService.uuidBytes(uuid));
                        try (ResultSet rows = statement.executeQuery()) {
                            if (rows.next()) {
                                placed = rows.getLong(1);
                                mined = rows.getLong(2);
                            }
                        }
                    }
                    Map<String, Long> values = new LinkedHashMap<>();
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT industry_id, development FROM player_industry_stats"
                                            + " WHERE player_uuid = ? ORDER BY industry_id")) {
                        statement.setBytes(1, AccountService.uuidBytes(uuid));
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                values.put(rows.getString(1), rows.getLong(2));
                            }
                        }
                    }
                    return new PlayerSummary(placed, mined, Map.copyOf(values));
                });
    }

    private boolean enabled() {
        return Arrays.asList(config.statisticsServers).contains(config.serverId);
    }

    private boolean collecting(MinecraftServer server) {
        return enabled() && !journalFull && RuleManager.collecting(server);
    }

    public void activate(RuleSnapshot snapshot) {
        if (!isDrained())
            throw new IllegalStateException("Cannot change rules with pending statistics");
        configHash = snapshot.hash();
        activeRuleHash = configHash.clone();
        eventRules = new GameEventRules(snapshot.events);
    }

    private static boolean eligible(ServerPlayer player) {
        GameType mode = player.gameMode();
        return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
    }

    private static Map<PlayerKey, PlayerCounts> copyPlayers(Map<PlayerKey, PlayerCounts> source) {
        Map<PlayerKey, PlayerCounts> copy = new HashMap<>();
        source.forEach(
                (key, value) -> {
                    PlayerCounts counts = new PlayerCounts();
                    counts.placed = value.placed;
                    counts.mined = value.mined;
                    copy.put(key, counts);
                });
        return copy;
    }

    static byte[] ruleHash() {
        return activeRuleHash.clone();
    }

    private static byte[] computeLoadedRuleHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(computeBuiltInRuleHash());
            List<String> members = new ArrayList<>();
            for (var item : BuiltInRegistries.ITEM) {
                String matched = null;
                for (BuiltInIndustry industry : BuiltInIndustry.values()) {
                    if (item.builtInRegistryHolder()
                            .is(IndustryTags.forIndustry(industry).craft())) {
                        if (matched != null) {
                            throw new IllegalStateException(
                                    "Item is assigned to two craft industries: "
                                            + BuiltInRegistries.ITEM.getKey(item));
                        }
                        matched = industry.path();
                    }
                }
                if (matched != null) {
                    members.add("craft/" + matched + "/" + BuiltInRegistries.ITEM.getKey(item));
                }
            }
            for (var block : BuiltInRegistries.BLOCK) {
                for (IndustryMatcher.Action action :
                        List.of(IndustryMatcher.Action.PLACE, IndustryMatcher.Action.MINE)) {
                    String matched = null;
                    for (BuiltInIndustry industry : BuiltInIndustry.values()) {
                        IndustryTagSet tags = IndustryTags.forIndustry(industry);
                        if (block.builtInRegistryHolder()
                                .is(
                                        action == IndustryMatcher.Action.PLACE
                                                ? tags.place()
                                                : tags.mine())) {
                            if (matched != null) {
                                throw new IllegalStateException(
                                        "Block is assigned to two "
                                                + action
                                                + " industries: "
                                                + BuiltInRegistries.BLOCK.getKey(block));
                            }
                            matched = industry.path();
                        }
                    }
                    if (matched != null) {
                        members.add(
                                action.name()
                                        + "/"
                                        + matched
                                        + "/"
                                        + BuiltInRegistries.BLOCK.getKey(block));
                    }
                }
            }
            members.sort(String::compareTo);
            members.forEach(
                    member -> digest.update((member + "\n").getBytes(StandardCharsets.UTF_8)));
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] computeBuiltInRuleHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (BuiltInIndustry industry : BuiltInIndustry.values()) {
                for (String action : List.of("craft", "place", "mine")) {
                    String registry = action.equals("craft") ? "item" : "block";
                    updateResourceHash(
                            digest,
                            "/data/contribution/tags/"
                                    + registry
                                    + "/"
                                    + action
                                    + "_"
                                    + industry.path()
                                    + ".json");
                }
            }
            updateResourceHash(
                    digest, "/data/contribution/contribution/game_event_industry_map.json");
            return digest.digest();
        } catch (NoSuchAlgorithmException | IOException error) {
            throw new IllegalStateException("Cannot fingerprint built-in industry rules", error);
        }
    }

    private static void updateResourceHash(MessageDigest digest, String path) throws IOException {
        digest.update(path.getBytes(StandardCharsets.UTF_8));
        try (var stream = StatisticsService.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing industry resource: " + path);
            }
            digest.update(stream.readAllBytes());
        }
    }

    record IndustryDayKey(long day, String industry) {}

    record PlayerKey(UUID uuid, String name) {}

    record PlayerIndustryKey(UUID uuid, String industry) {}

    record DistanceDayKey(long day, UUID uuid, String mode) {}

    private record DedupKey(
            String server,
            String dimension,
            UUID player,
            IndustryMatcher.Action action,
            long pos,
            String block) {}

    static final class PlayerCounts {
        long placed;
        long mined;
    }

    record Batch(
            UUID id,
            long sequence,
            byte[] configHash,
            Map<IndustryDayKey, Long> industry,
            Map<PlayerKey, PlayerCounts> player,
            Map<PlayerIndustryKey, Long> playerIndustry,
            Map<DistanceDayKey, Long> distance,
            Map<MeasuredStatistics.Key, Long> measured) {
        Batch(
                UUID id,
                long sequence,
                byte[] configHash,
                Map<IndustryDayKey, Long> industry,
                Map<PlayerKey, PlayerCounts> player,
                Map<PlayerIndustryKey, Long> playerIndustry,
                Map<DistanceDayKey, Long> distance) {
            this(id, sequence, configHash, industry, player, playerIndustry, distance, Map.of());
        }

        boolean affectsThrough(long day) {
            return industry.keySet().stream().anyMatch(key -> key.day() <= day)
                    || distance.keySet().stream().anyMatch(key -> key.day() <= day)
                    || measured.keySet().stream().anyMatch(key -> key.day() <= day);
        }

        private int keyCount() {
            return industry.size()
                    + player.size()
                    + playerIndustry.size()
                    + distance.size()
                    + measured.size();
        }

        private String canonical() {
            List<String> entries = new ArrayList<>();
            industry.forEach((key, value) -> entries.add("I:" + key + ":" + value));
            player.forEach(
                    (key, value) ->
                            entries.add("P:" + key + ":" + value.placed + ":" + value.mined));
            playerIndustry.forEach((key, value) -> entries.add("PI:" + key + ":" + value));
            distance.forEach((key, value) -> entries.add("D:" + key + ":" + value));
            measured.forEach((key, value) -> entries.add("M:" + key + ":" + value));
            entries.sort(String::compareTo);
            return String.join("\n", entries);
        }
    }
}
