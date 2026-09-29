package cn.contribution.database;

import cn.contribution.ContributionMod;
import cn.contribution.config.DatabaseConfig;
import cn.contribution.config.ServerConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class DatabaseService implements AutoCloseable {
    private final ServerConfig config;
    private final Path embeddedDatabasePath;
    private final AtomicReference<DatabaseState> state = new AtomicReference<>(DatabaseState.UNAVAILABLE);
    private final ThreadPoolExecutor executor;
    private volatile HikariDataSource dataSource;

    public DatabaseService(ServerConfig config) {
        this(config, Path.of("contribution-data", "contribution"));
    }

    public DatabaseService(ServerConfig config, Path embeddedDatabasePath) {
        this.config = Objects.requireNonNull(config, "config");
        this.embeddedDatabasePath = Objects.requireNonNull(embeddedDatabasePath, "embeddedDatabasePath").toAbsolutePath().normalize();
        this.executor = new ThreadPoolExecutor(
                config.databaseThreads,
                config.databaseThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.databaseQueueCapacity),
                new DatabaseThreadFactory(),
                new RejectTask()
        );
    }

    public CompletableFuture<DatabaseState> start() {
        if (state.getAndSet(DatabaseState.STARTING) == DatabaseState.STARTING) {
            return CompletableFuture.completedFuture(DatabaseState.STARTING);
        }

        return CompletableFuture.supplyAsync(() -> {
            HikariDataSource newDataSource = null;
            try {
                if (embedded()) Files.createDirectories(embeddedDatabasePath.getParent());
                newDataSource = new HikariDataSource(createPoolConfig(config.database));
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
                ContributionMod.LOGGER.info("Database is available for server_id={}", config.serverId);
            } catch (RuntimeException | IOException exception) {
                if (newDataSource != null) {
                    newDataSource.close();
                }
                state.set(DatabaseState.UNAVAILABLE);
                ContributionMod.LOGGER.error(
                        "Database startup failed for server_id={}; economic writes remain disabled",
                        config.serverId,
                        exception
                );
            }
            return state.get();
        }, executor);
    }

    public DatabaseState state() {
        return state.get();
    }

    public boolean usesEmbeddedDatabase() {
        return embedded();
    }

    public <T> CompletableFuture<T> read(Function<Connection, T> operation) {
        return submit(connection -> operation.apply(connection));
    }

    public <T> CompletableFuture<T> transaction(SqlOperation<T> operation) {
        return submit(connection -> {
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
                throw new DatabaseUnavailableException("Database transaction failed", exception);
            }
        });
    }

    private <T> CompletableFuture<T> submit(SqlOperation<T> operation) {
        if (state.get() != DatabaseState.AVAILABLE || dataSource == null) {
            return CompletableFuture.failedFuture(new DatabaseUnavailableException("Database is not available"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    return operation.apply(connection);
                } catch (DatabaseUnavailableException exception) {
                    throw exception;
                } catch (SQLException exception) {
                    markUnavailableOnConnectionFailure(exception);
                    throw new DatabaseUnavailableException("Database operation failed", exception);
                } catch (Exception exception) {
                    throw new DatabaseUnavailableException("Database operation failed", exception);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new DatabaseUnavailableException("Database task queue is full", exception));
        }
    }

    private void markUnavailableOnConnectionFailure(SQLException exception) {
        if (exception.getSQLState() != null && exception.getSQLState().startsWith("08")) {
            state.set(DatabaseState.UNAVAILABLE);
        }
    }

    private HikariConfig createPoolConfig(DatabaseConfig database) {
        HikariConfig pool = new HikariConfig();
        pool.setPoolName("contribution-" + config.serverId);
        pool.setJdbcUrl(embedded() ? "jdbc:h2:file:" + embeddedDatabasePath.toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE" : database.jdbcUrl);
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
        Flyway flyway = Flyway.configure(DatabaseService.class.getClassLoader())
                .dataSource(source)
                .locations(embedded() ? "classpath:db/migration/h2" : "classpath:db/migration/mysql")
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
    public synchronized void close() {
        state.set(DatabaseState.STOPPED);
        HikariDataSource source = dataSource;
        dataSource = null;
        if (source != null) {
            source.close();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
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
