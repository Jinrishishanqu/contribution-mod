package cn.contribution.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Page hierarchy and cursor history are independent; pagination never becomes an Escape parent. */
public final class ContributionNavigation {
    private final Map<String, Deque<String>> pages = new HashMap<>();
    private final Map<String, String> currentPages = new HashMap<>();

    public static String module(String request) {
        var p = request.strip().split("\\s+", 4);
        if (p[0].equals("history")) return "history:" + (p.length > 1 ? p[1] : "self") + ":" + (p.length > 3 ? p[3] : "");
        if (p[0].equals("admin") || p[0].equals("accounts") || p[0].equals("admin_search")) return "admin";
        if (p[0].equals("account") || p[0].equals("stats")) return "profile:" + (p.length > 1 ? p[1] : "self");
        return p[0];
    }
    public static String parent(String request) {
        var p = request.strip().split("\\s+", 3);
        return switch (p[0]) {
            case "home", "close" -> "close";
            case "admin", "accounts", "admin_search", "industries", "checkin" -> "home";
            case "account", "stats" -> p.length < 2 || p[1].equals("self") ? "home" : "admin";
            case "history" -> p.length > 1 && p[1].equals("*") ? "admin" : "account " + (p.length > 1 ? p[1] : "self");
            default -> "home";
        };
    }
    public void visit(String request) {
        String key = module(request), previous = currentPages.put(key, request);
        var parts = request.split("\\s+", 4);
        boolean first = key.startsWith("history:") ? parts.length < 3 || parts[2].equals("-")
                : key.equals("admin") && (parts.length < 2 || parts[0].equals("admin_search"));
        if (first) pages.remove(key);
        else if (previous != null && !previous.equals(request)) {
            var stack = pages.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            if (stack.size() >= 128) stack.removeLast();
            stack.push(previous);
        }
    }
    public boolean hasPrevious(String request) { return !pages.getOrDefault(module(request), new ArrayDeque<>()).isEmpty(); }
    public String previous(String request) {
        var stack = pages.get(module(request));
        String result = stack == null || stack.isEmpty() ? request : stack.pop();
        currentPages.put(module(result), result);
        return result;
    }
}
