package cn.contribution.database;

public final class DatabaseUnavailableException extends RuntimeException {
    public DatabaseUnavailableException(String message) {
        super(message);
    }

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
