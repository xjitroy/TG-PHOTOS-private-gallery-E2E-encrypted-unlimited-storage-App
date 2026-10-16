package app.televault.crypto;

/** Owner-selected six-digit option; longer phrases remain recommended and compatible. */
public final class RecoveryPhrasePolicy {
    private RecoveryPhrasePolicy() {}
    public static boolean isSixDigit(CharSequence value) {
        if (value == null || value.length() != 6) return false;
        for (int i = 0; i < 6; i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }
    public static boolean accepts(CharSequence value) {
        if (value == null || value.length() > 512) return false;
        if (value.length() >= 12) return true;
        if (value.length() < 6) return false;
        for (int i=0;i<value.length();i++) if (value.charAt(i)<'0' || value.charAt(i)>'9') return false;
        return true;
    }
    public static boolean accepts(char[] value) {
        if (value == null) return false;
        if (value.length >= 12 && value.length <= 512) return true;
        if (value.length < 6 || value.length > 512) return false;
        for (char c : value) if (c < '0' || c > '9') return false;
        return true;
    }
}
