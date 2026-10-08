package cn.contribution.database;

import cn.contribution.ContributionMod;
import cn.contribution.config.DatabaseConfig;
import cn.contribution.config.ServerConfig;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class DatabaseService implements AutoCloseable {
    private final ServerConfig config;
    private final Path embeddedDatabasePath;
    private final AtomicReference<DatabaseState> state =
            new AtomicReference<>(DatabaseState.UNAVAILABLE);
    private final ThreadPoolExecutor executor;
    private volatile HikariDataSource dataSource;
    private CompletableFuture<DatabaseState> startup;

    public DatabaseService(ServerConfig config) {
        this(config, Path.of("contribution-data", "contribution"));
    }

    public DatabaseService(ServerConfig config, Path embeddedDatabasePath) {
        this.config = Objects.requireNonNull(config, "config");
        this.embeddedDatabasePath =
                Objects.requireNonNull(embeddedDatabasePath, "embeddedDatabasePath")
                        .toAbsolutePath()
                        .normalize();
        this.executor =
                new ThreadPoolExecutor(
                        config.databaseThreads,
                        config.databaseThreads,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(config.databaseQueueCapacity),
                        new DatabaseThreadFactory(),
                        new RejectTask());
    }

    public synchronized CompletableFuture<DatabaseState> start() {
        if (state.get() == DatabaseState.STOPPED || state.get() == DatabaseState.AVAILABLE) {
            return CompletableFuture.completedFuture(state.get());
        }
        if (state.get() == DatabaseState.STARTING) return startup;
        state.set(DatabaseState.STARTING);
        startup =
                execute(
                        () -> {
                            HikariDataSource newDataSource = null;
                            try {
                                if (embedded())
                                    Files.createDirectories(embeddedDatabasePath.getParent());
                                newDataSource =
                                        new HikariDataSource(createPoolConfig(config.database));
                                migrateOrValidate(newDataSource);
                                synchronized (this) {
                                    if (state.get() == DatabaseState.STOPPED) {
                                        newDataSource.close();
                                        return DatabaseState.STOPPED;
                                    }
                                    HikariDataSource previous = dataSource;
                                    dataSource = newDataSource;
                                    state.set(DatabaseState.AVAILABLE);
                                    if (previous != null) {
                                        previous.close();
                                    }
                                }
                                ContributionMod.LOGGER.info(
                                        "Database is available for server_id={}", config.serverId);
                            } catch (RuntimeException | IOException exception) {
                                if (newDataSource != null) {
                                    newDataSource.close();
                                }
                                state.compareAndSet(
                                        DatabaseState.STARTING, DatabaseState.UNAVAILABLE);
                                ContributionMod.LOGGER.error(
                                        "Database startup failed for server_id={}; economic writes"
                                                + " remain disabled",
                                        config.serverId,
                                        exception);
                            }
                            return state.get();
                        });
        startup.whenComplete(
                (result, failure) -> {
                    if (failure != null)
                        state.compareAndSet(DatabaseState.STARTING, DatabaseState.UNAVAILABLE);
                });
        return startup;
    }

    public DatabaseState state() {
        return state.get();
    }

    public boolean usesEmbeddedDatabase() {
        return embedded();
    }

    public <T> CompletableFuture<T> read(Function<Connection, T> operation) {
        return submit(operation::apply);
    }

    public <T> CompletableFuture<T> transaction(SqlOperation<T> operation) {
        return submit(
                connection -> {
                    boolean previousAutoCommit;
                    try {
                        previousAutoCommit = connection.getAutoCommit();
                        connection.setAutoCommit(false);
                        try {
                            T result = operation.apply(connection);
                            connection.commit();
                            return result;
                        } catch (Throwable throwable) {
                            connection.rollback();
                            throw throwable;
                        } finally {
                            connection.setAutoCommit(previousAutoCommit);
                        }
                    } catch (SQLException exception) {
                        markUnavailableOnConnectionFailure(exception);
                        throw new DatabaseUnavailableException(
                                "Database transaction failed", exception);
                    }
                });
    }

    private <T> CompletableFuture<T> submit(SqlOperation<T> operation) {
        HikariDataSource source = dataSource;
        if (state.get() != DatabaseState.AVAILABLE || source == null) {
            return CompletableFuture.failedFuture(
                    new DatabaseUnavailableException("Database is not available"));
        }
        return execute(
                () -> {
                    try (Connection connection = source.getConnection()) {
                        return operation.apply(connection);
                    } catch (DatabaseUnavailableException exception) {
                        throw exception;
                    } catch (SQLException exception) {
                        markUnavailableOnConnectionFailure(exception);
                        throw new DatabaseUnavailableException(
                                "Database operation failed", exception);
                    } catch (Exception exception) {
                        throw new DatabaseUnavailableException(
                                "Database operation failed", exception);
                    }
                });
    }

    private void markUnavailableOnConnectionFailure(SQLException exception) {
        if (exception.getSQLState() != null && exception.getSQLState().startsWith("08")) {
            state.compareAndSet(DatabaseState.AVAILABLE, DatabaseState.UNAVAILABLE);
        }
    }

    private HikariConfig createPoolConfig(DatabaseConfig database) {
        HikariConfig pool = new HikariConfig();
        pool.setPoolName("contribution-" + config.serverId);
        pool.setJdbcUrl(
                embedded()
                        ? "jdbc:h2:file:"
                                + embeddedDatabasePath.toString().replace('\\', '/')
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE"
                        : database.jdbcUrl);
        pool.setUsername(embedded() ? "sa" : database.username);
        pool.setPassword(embedded() ? "" : database.password);
        pool.setDriverClassName(embedded() ? "org.h2.Driver" : "com.mysql.cj.jdbc.Driver");
        if (!embedded() && config.mainServer) {
            pool.addDataSourceProperty("createDatabaseIfNotExist", "true");
        }
        pool.setConnectionInitSql(embedded() ? "SET TIME ZONE 'UTC'" : "SET time_zone = '+00:00'");
        pool.setMaximumPoolSize(database.maximumPoolSize);
        pool.setMinimumIdle(database.minimumIdle);
        pool.setConnectionTimeout(database.connectionTimeoutMs);
        pool.setValidationTimeout(database.validationTimeoutMs);
        pool.setMaxLifetime(database.maxLifetimeMs);
        pool.setKeepaliveTime(database.keepaliveTimeMs);
        pool.setAutoCommit(true);
        pool.setInitializationFailTimeout(1);
        return pool;
    }

    private void migrateOrValidate(HikariDataSource source) {
        Flyway flyway =
                Flyway.configure(DatabaseService.class.getClassLoader())
                        .dataSource(source)
                        .locations(
                                embedded()
                                        ? "classpath:db/migration/h2"
                                        : "classpath:db/migration/mysql")
                        .validateMigrationNaming(true)
                        .load();
        if (embedded() || config.mainServer) {
            flyway.migrate();
        } else {
            flyway.validate();
        }
    }

    private boolean embedded() {
        return "embedded".equals(config.database.mode);
    }

    @Override
    public void close() {
        HikariDataSource source;
        synchronized (this) {
            if (state.get() == DatabaseState.STOPPED) return;
            state.set(DatabaseState.STOPPED);
            source = dataSource;
            dataSource = null;
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                cancelQueuedTasks();
            }
        } catch (InterruptedException exception) {
            cancelQueuedTasks();
            Thread.currentThread().interrupt();
        } finally {
            if (source != null) source.close();
        }
    }

    /** Every accepted task owns its completion, including tasks removed during shutdown. */
    private <T> CompletableFuture<T> execute(java.util.concurrent.Callable<T> operation) {
        DatabaseTask<T> task = new DatabaseTask<>(operation);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            task.result.completeExceptionally(
                    new DatabaseUnavailableException(
                            "Database task queue is full or stopped", exception));
        }
        return task.result;
    }

    private void cancelQueuedTasks() {
        for (Runnable task : executor.shutdownNow()) {
            if (task instanceof DatabaseTask<?> databaseTask) {
                databaseTask.result.completeExceptionally(
                        new DatabaseUnavailableException("Database stopped before task execution"));
            }
        }
    }

    private static final class DatabaseTask<T> implements Runnable {
        private final java.util.concurrent.Callable<T> operation;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private DatabaseTask(java.util.concurrent.Callable<T> operation) {
            this.operation = operation;
        }

        @Override
        public void run() {
            try {
                result.complete(operation.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }
    }

    @FunctionalInterface
    public interface SqlOperation<T> {
        T apply(Connection connection) throws Exception;
    }

    private static final class DatabaseThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "contribution-db-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class RejectTask implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            throw new RejectedExecutionException("Database task queue is full");
        }
    }
}
