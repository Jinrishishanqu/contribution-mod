package cn.contribution.client;

/** Pure responsive geometry; no rendering or per-frame catalog work. */
public record WeaponSkinLayout(
        int left, int top, int columns, int rows, int cardWidth, int cardHeight) {
    public int capacity() {
        return columns * rows;
    }

    public static WeaponSkinLayout of(int width, int height) {
        int panel = Math.max(32, Math.min(960, width - 36));
        int columns = Math.max(1, Math.min(8, panel / 100));
        int top = Math.min(32, Math.max(8, height / 5));
        int available = Math.max(16, height - top - 40);
        int rows = Math.max(1, Math.min(6, available / 64));
        return new WeaponSkinLayout(
                (width - panel) / 2,
                top,
                columns,
                rows,
                panel / columns,
                Math.min(120, available / rows));
    }
}
