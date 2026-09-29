package cn.contribution.config;

public final class DatabaseConfig {
    public String mode = "embedded";
    public String jdbcUrl = "jdbc:mysql://127.0.0.1:3306/contribution?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC";
    public String username = "contribution";
    public String password = "";
    public int maximumPoolSize = 4;
    public int minimumIdle = 1;
    public long connectionTimeoutMs = 3_000;
    public long validationTimeoutMs = 1_000;
    public long maxLifetimeMs = 1_500_000;
    public long keepaliveTimeMs = 120_000;
}
