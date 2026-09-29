package cn.contribution.account;

import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;

import java.sql.DriverManager;
import java.util.UUID;

/** Explicit opt-in check of main-server database creation on the project test MySQL. */
public final class MySqlCreationChecks {
    public static void main(String[] args) throws Exception {
        String name = "contribution_autocreate_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ServerConfig config = new ServerConfig();
        config.database.mode = "mysql";
        config.database.jdbcUrl = "jdbc:mysql://127.0.0.1:23306/" + name + "?serverTimezone=UTC";
        config.database.username = "root";
        try (var database = new DatabaseService(config)) {
            if (database.start().join() != DatabaseState.AVAILABLE) throw new AssertionError("MySQL database auto-creation failed");
            database.transaction(connection -> {
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM flyway_schema_history");
                     var rows = query.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 6) throw new AssertionError("business tables were not migrated");
                }
                return null;
            }).join();
        }
        try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:23306/?serverTimezone=UTC", "root", "");
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + name + "`");
        }
        System.out.println("MYSQL_AUTO_CREATE_PASS: missing database created and six migrations applied");
    }
}
