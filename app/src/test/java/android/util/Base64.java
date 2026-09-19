package android.util;

/**
 * JVM-only adapter for the Base64 operations used by dSIM. Android's mockable jar returns null.
 * This is NOT Android API compatibility evidence: lint/device tests cover that separately.
 * Unsupported flag combinations fail loudly rather than silently imitating Android behaviour.
 */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    private Base64() {}

    public static String encodeToString(byte[] input, int flags) {
        if (flags == (URL_SAFE | NO_WRAP | NO_PADDING)) {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input);
        }
        if (flags == NO_WRAP) {
            return java.util.Base64.getEncoder().encodeToString(input);
        }
        throw new UnsupportedOperationException("Untested Base64 encoding flags: " + flags);
    }

    public static byte[] decode(String input, int flags) {
        if (flags != DEFAULT) {
            throw new UnsupportedOperationException("Untested Base64 decoding flags: " + flags);
        }
        return java.util.Base64.getDecoder().decode(input.replaceAll("\\s", ""));
    }
}
