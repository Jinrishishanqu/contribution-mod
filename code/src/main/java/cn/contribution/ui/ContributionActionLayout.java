package cn.contribution.ui;

/** Shared geometry for local action rows, independent of Minecraft client classes. */
public final class ContributionActionLayout {
    private ContributionActionLayout() {}

    public record Bounds(int x, int y, int width, int height) {}

    public static Bounds bounds(String view, int count, int index, int width, int height) {
        int columns = Math.min(4, Math.max(1, count));
        int rows = (count + columns - 1) / columns;
        int row = index / columns;
        int column = index % columns;
        // Ranking actions are four categories followed by previous, refresh and next.
        if (view.equals("ranking") && count == 7 && row == 1) columns = 3;
        int buttonWidth = Math.max(30, (width - 24 - (columns - 1) * 5) / columns);
        return new Bounds(
                12 + column * (buttonWidth + 5),
                height - 55 - (rows - row - 1) * 24,
                buttonWidth,
                19);
    }
}
