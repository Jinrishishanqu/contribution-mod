package cn.contribution.client;

import java.util.List;
import java.util.Set;

/** One filtered view of an immutable dashboard snapshot; no world or server state retained. */
final class StockListCache<T> {
    private String term;
    private int sort;
    private boolean owned;
    private Set<String> industries = Set.of();
    private List<T> rows = List.of();

    boolean matches(String term, int sort, boolean owned, Set<String> industries) {
        return term.equals(this.term)
                && sort == this.sort
                && owned == this.owned
                && industries.equals(this.industries);
    }

    List<T> rows() {
        return rows;
    }

    List<T> replace(String term, int sort, boolean owned, Set<String> industries, List<T> rows) {
        this.term = term;
        this.sort = sort;
        this.owned = owned;
        this.industries = Set.copyOf(industries);
        this.rows = List.copyOf(rows);
        return this.rows;
    }
}
