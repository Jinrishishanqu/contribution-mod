package cn.contribution.account;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A bounded, server-validated search of the online transaction table. */
public record HistoryFilter(
        String type,
        String source,
        String serverId,
        Instant fromInclusive,
        Instant untilExclusive,
        String commandArguments) {
    private static final Set<String> TYPES =
            Set.of(
                    "ADMIN",
                    "CHECK_IN",
                    "EVENT_CHECK_IN",
                    "DEVELOP",
                    "SHOP_BUY",
                    "EXCHANGE",
                    "SPEND",
                    "STOCK",
                    "BONUS",
                    "EXTERNAL",
                    "REFUND",
                    "TAX",
                    "STOCK_BUY",
                    "STOCK_SELL");

    public static HistoryFilter empty() {
        return new HistoryFilter(null, null, null, null, null, "");
    }

    public static HistoryFilter parse(String input, int maxAgeDays) {
        if (input == null || input.isBlank() || maxAgeDays < 1 || maxAgeDays > 365) {
            throw new IllegalArgumentException("筛选条件不能为空");
        }
        Map<String, String> values = new HashMap<>();
        for (String token : input.trim().split("\\s+")) {
            int separator = token.indexOf('=');
            if (separator < 1 || separator == token.length() - 1) {
                throw new IllegalArgumentException("筛选条件格式应为 名称=值");
            }
            String key = token.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = token.substring(separator + 1);
            if (!Set.of("type", "source", "server", "from", "to").contains(key)
                    || values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("筛选条件未知或重复：" + key);
            }
        }
        String type = values.get("type");
        if (type != null) {
            type = type.toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("未知的流水类型");
            }
        }
        String source = safeId(values.get("source"), 128, "来源");
        String server = safeId(values.get("server"), 64, "子服");
        LocalDate fromDate = parseDate(values.get("from"), "开始日期");
        LocalDate toDate = parseDate(values.get("to"), "结束日期");
        LocalDate earliest = LocalDate.now(ZoneOffset.UTC).minusDays(maxAgeDays);
        if (maxAgeDays == 365 && fromDate == null && toDate != null)
            fromDate = toDate.minusDays(364);
        if (maxAgeDays == 365 && fromDate != null) {
            if (toDate == null) toDate = fromDate.plusDays(364);
            if (java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) >= 365) {
                throw new IllegalArgumentException("管理员单次查询范围最多 365 天");
            }
        }
        if (maxAgeDays != 365 && fromDate != null && fromDate.isBefore(earliest)) {
            throw new IllegalArgumentException("开始日期超出当前可查询范围");
        }
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        if (maxAgeDays != 365 && toDate != null && toDate.isBefore(earliest)) {
            throw new IllegalArgumentException("结束日期超出当前可查询范围");
        }
        StringBuilder command = new StringBuilder();
        append(command, "type", type);
        append(command, "source", source);
        append(command, "server", server);
        append(command, "from", fromDate == null ? null : fromDate.toString());
        append(command, "to", toDate == null ? null : toDate.toString());
        return new HistoryFilter(
                type,
                source,
                server,
                fromDate == null ? null : fromDate.atStartOfDay().toInstant(ZoneOffset.UTC),
                toDate == null ? null : toDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                command.toString());
    }

    private static String safeId(String value, int maxLength, String name) {
        if (value != null && (value.length() > maxLength || !value.matches("[A-Za-z0-9_.:/-]+"))) {
            throw new IllegalArgumentException(name + "标识无效");
        }
        return value;
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1001 || date.getYear() > 9998)
                throw new IllegalArgumentException(name + "超出数据库日期范围");
            return date;
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(name + "应使用 YYYY-MM-DD（UTC）", error);
        }
    }

    private static void append(StringBuilder command, String key, String value) {
        if (value != null) {
            if (!command.isEmpty()) {
                command.append(' ');
            }
            command.append(key).append('=').append(value);
        }
    }
}
