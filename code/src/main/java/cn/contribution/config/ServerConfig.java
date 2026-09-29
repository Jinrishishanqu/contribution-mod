package cn.contribution.config;

public final class ServerConfig {
    public String serverId = "survival";
    public boolean mainServer = true;
    public String[] statisticsServers = {"survival"};
    public int databaseThreads = 2;
    public int databaseQueueCapacity = 1_024;
    public DatabaseConfig database = new DatabaseConfig();
}
