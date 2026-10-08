package cn.contribution.database;

import cn.contribution.config.ServerConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deterministic lifecycle checks: no stopped restart and no orphaned queued futures. */
public final class DatabaseLifecycleChecks {
    public static void main(String[] args) throws Exception {
        var config = new ServerConfig();
        config.databaseThreads = 1;
        config.databaseQueueCapacity = 2;
        var database =
                new DatabaseService(
                        config,
                        Files.createTempDirectory(Path.of("build"), "database-lifecycle-")
                                .resolve("contribution"));
        try {
            var firstStart = database.start();
            var secondStart = database.start();
            if (!firstStart.isDone())
                require(firstStart == secondStart, "single in-flight startup");
            require(firstStart.get(30, TimeUnit.SECONDS) == DatabaseState.AVAILABLE, "available");
            require(
                    database.start().join() == DatabaseState.AVAILABLE,
                    "idempotent available start");
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var running =
                    database.transaction(
                            connection -> {
                                entered.countDown();
                                release.await();
                                return true;
                            });
            require(entered.await(5, TimeUnit.SECONDS), "worker entered");
            var queued = database.read(connection -> true);
            var queuedSecond = database.read(connection -> true);
            require(
                    database.read(connection -> true).isCompletedExceptionally(),
                    "bounded queue rejects");
            var closed = CompletableFuture.runAsync(database::close);
            closed.get(8, TimeUnit.SECONDS);
            require(
                    queued.isCompletedExceptionally() && queuedSecond.isCompletedExceptionally(),
                    "queued futures completed on close");
            running.handle((value, failure) -> null).get(2, TimeUnit.SECONDS);
            require(running.isDone(), "interrupted worker completed");
            require(database.state() == DatabaseState.STOPPED, "terminal stopped state");
            require(database.start().join() == DatabaseState.STOPPED, "stopped cannot restart");
            require(
                    database.read(connection -> true).isCompletedExceptionally(),
                    "stopped read fails promptly");
        } finally {
            database.close();
        }
        System.out.println(
                "DATABASE_LIFECYCLE_PASS: startup, bounded queue, close and terminal futures");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
