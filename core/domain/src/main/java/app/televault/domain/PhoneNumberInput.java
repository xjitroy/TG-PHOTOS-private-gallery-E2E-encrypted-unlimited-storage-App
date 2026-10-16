package app.televault.domain;

import java.util.Collection;

/** Split explicit international input by the longest known dialing code, never IP-guessed digits. */
public final class PhoneNumberInput {
    private PhoneNumberInput() {}
    public record Split(String code, String national, boolean international) {}
    public static Split split(String value, String selected, Collection<String> known) {
        StringBuilder normalized = new StringBuilder();
        value.codePoints().forEach(c -> { int digit = Character.digit(c, 10); if (digit >= 0) normalized.append(digit); });
        String digits = normalized.toString();
        if (value.stripLeading().startsWith("+")) {
            String code = known.stream().filter(c -> c.matches("[0-9]{1,4}") && digits.startsWith(c))
                    .max(java.util.Comparator.comparingInt(String::length)).orElse("");
            return new Split(code, digits.substring(code.length()), true);
        }
        return new Split(selected, digits, false);
    }
    public static String international(String code, String national) {
        if (!code.matches("[0-9]{1,4}") || !national.matches("[0-9]+")) return "";
        String digits = code + national;
        return digits.length() <= 15 ? "+" + digits : "";
    }
}
