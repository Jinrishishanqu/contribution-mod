package cn.contribution.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Convert persisted UTC instants only at the presentation boundary. */
public final class LedgerDisplay {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private LedgerDisplay() {}

    public static String time(Instant instant, ZoneId zone) {
        return TIME.withZone(zone).format(instant);
    }

    public static String shortTime(Instant instant, ZoneId zone) {
        return SHORT_TIME.withZone(zone).format(instant);
    }
}
