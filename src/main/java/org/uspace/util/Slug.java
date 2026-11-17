package org.uspace.util;

import java.text.Normalizer;

/**
 * Turns a human-readable name into a candidate {@code ApplicationId}.
 *
 * <p>The result must satisfy the id grammar ({@code [a-z0-9]} with inner dashes, at most 64
 * characters) because the id doubles as a directory name and as the suffix of generated wrappers and
 * launch files. Anything that cannot be represented — spaces, punctuation, an empty name — becomes a
 * separator or the fallback {@code app}, never a silent truncation that could collide with a
 * different application.
 *
 * <p>Accented letters are folded rather than dropped, so {@code Média Café} becomes
 * {@code media-cafe} instead of {@code m-dia-caf}. Uniqueness is <em>not</em> this class's job: two
 * names can legitimately produce the same slug, and only the caller knows what is already taken.
 */
public final class Slug {

    /** Matches {@code ApplicationId}'s limit, so the result is always a legal id. */
    private static final int MAX_LENGTH = 64;

    private static final String FALLBACK = "app";

    private Slug() {
    }

    public static String from(String text) {
        if (text == null || text.isBlank()) {
            return FALLBACK;
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(MAX_LENGTH);
        boolean pendingDash = false;
        for (int i = 0; i < folded.length() && out.length() < MAX_LENGTH; i++) {
            char c = folded.charAt(i);
            if (isCombiningMark(c)) {
                continue;
            }
            char lower = Character.toLowerCase(c);
            if ((lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9')) {
                out.append(lower);
                pendingDash = false;
            } else if (!pendingDash && out.length() > 0) {
                out.append('-');
                pendingDash = true;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.length() == 0 ? FALLBACK : out.toString();
    }

    /**
     * True for the accents of a decomposed character (NFD gives {@code e} + combining acute), which
     * is what lets {@code é} contribute {@code e} instead of a separator.
     */
    private static boolean isCombiningMark(char c) {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}
