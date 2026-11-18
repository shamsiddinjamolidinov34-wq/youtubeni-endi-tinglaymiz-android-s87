package uz.tinglaymiz.app;

import java.util.Locale;

/** Fayl va papka nomlari bilan ishlash. Android'ga bog'liq emas. */
final class Names {

    private Names() {
    }

    /**
     * Sarlavhani fayl nomiga yaroqli qiladi: taqiqlangan belgilarni olib tashlaydi,
     * ketma-ket bo'shliqlarni bittaga keltiradi va nomni UTF-8 hisobida maxBytes dan oshirmaydi.
     */
    static String safe(String s, int maxBytes) {
        if (s == null) {
            s = "";
        }
        StringBuilder b = new StringBuilder();
        boolean pendingSpace = false;
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.FORMAT) {
                continue; // ko'rinmas belgilar jimgina olib tashlanadi
            }
            boolean separator = cp < 32 || cp == 127
                    || "\\/:*?\"<>|".indexOf(cp) >= 0
                    || Character.isISOControl(cp)
                    || Character.isWhitespace(cp)
                    || Character.isSpaceChar(cp);
            if (separator) {
                pendingSpace = b.length() > 0;
                continue;
            }
            int add = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            int need = add + (pendingSpace ? 1 : 0);
            if (bytes + need > maxBytes) {
                break;
            }
            if (pendingSpace) {
                b.append(' ');
                pendingSpace = false;
            }
            b.appendCodePoint(cp);
            bytes += need;
        }
        int start = 0;
        while (start < b.length() && b.charAt(start) == '.') {
            start++;
        }
        int end = b.length();
        while (end > start && (b.charAt(end - 1) == '.' || b.charAt(end - 1) == ' ')) {
            end--;
        }
        String r = b.substring(start, end);
        return r.isEmpty() ? "nomsiz" : r;
    }

    /** Pleylistdagi tartib raqami: 001, 002 ... (raqamlar soni umumiy songa qarab). */
    static String index(int i, int total) {
        int w = Math.max(2, String.valueOf(Math.max(total, 1)).length());
        return String.format(Locale.ROOT, "%0" + w + "d", i);
    }
}
