package cn.contribution.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** Fixed display-cell columns for vanilla Dialog messages, which center each body independently. */
final class DialogTable {
    private static final FontDescription.Resource UNIFORM =
            new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));

    private DialogTable() { }

    static Component text(String value) {
        return Component.literal(value).withStyle(style -> style.withFont(UNIFORM));
    }

    static String row(int[] widths, boolean[] rightAligned, String... cells) {
        if (widths.length != cells.length || rightAligned.length != cells.length)
            throw new IllegalArgumentException("Column count mismatch");
        StringBuilder line = new StringBuilder();
        for (int column = 0; column < cells.length; column++) {
            if (column > 0) line.append(" | ");
            String value = fit(cells[column], widths[column]);
            int padding = widths[column] - displayWidth(value);
            if (rightAligned[column]) line.append(" ".repeat(padding));
            line.append(value);
            if (!rightAligned[column]) line.append(" ".repeat(padding));
        }
        return line.toString();
    }

    static int displayWidth(String text) {
        return text.codePoints().map(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.KATAKANA ? 2 : 1).sum();
    }

    private static String fit(String text, int width) {
        if (width < 1) throw new IllegalArgumentException("Column width must be positive");
        if (displayWidth(text) <= width) return text;
        StringBuilder clipped = new StringBuilder();
        int used = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            String glyph = new String(Character.toChars(codePoint));
            int glyphWidth = displayWidth(glyph);
            if (used + glyphWidth + 1 > width) break;
            clipped.append(glyph); used += glyphWidth;
            index += Character.charCount(codePoint);
        }
        return clipped.append('…').toString();
    }
}
