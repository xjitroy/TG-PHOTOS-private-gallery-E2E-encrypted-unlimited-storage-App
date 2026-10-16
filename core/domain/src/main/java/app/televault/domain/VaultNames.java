package app.televault.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Human labels never determine encryption keys, directory paths or Vault identity. */
public final class VaultNames {
    public static final int MAX_CODE_POINTS = 80;
    private VaultNames() { }

    public static String generated(long createdAt, ZoneId zone, String id) {
        String suffix = UUID.fromString(id).toString().substring(0, 8);
        String date = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(createdAt).atZone(zone));
        return "Vault TG Photos E2EE · " + date + " · " + suffix;
    }

    public static String clean(String requested) {
        if (requested == null) return "";
        StringBuilder result = new StringBuilder();
        requested.codePoints().forEach(c -> {
            if (Character.isISOControl(c) || c == 0x200e || c == 0x200f || c >= 0x202a && c <= 0x202e || c >= 0x2066 && c <= 0x2069) return;
            result.appendCodePoint(c);
        });
        String value = result.toString().trim();
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) value = value.substring(0, value.offsetByCodePoints(0, MAX_CODE_POINTS));
        return value;
    }

    public static String forCreation(String requested, long createdAt, ZoneId zone, String id) {
        String value = clean(requested);
        return value.isEmpty() ? generated(createdAt, zone, id) : value;
    }

    public static String display(String requested, long createdAt, ZoneId zone, String id) {
        String value = clean(requested);
        if (!value.isEmpty()) return value;
        return createdAt > 0 ? generated(createdAt, zone, id) : "Private Vault · " + UUID.fromString(id).toString().substring(0, 8);
    }
}
