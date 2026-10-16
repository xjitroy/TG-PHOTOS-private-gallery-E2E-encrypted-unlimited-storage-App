package app.televault.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Stable, bounded export names; date bounds use the same owner timezone as the picker. */
public final class CloudExportNaming {
    private CloudExportNaming() {}
    public static void validate(int kinds, int categories, long from, long until, String zone) {
        if ((kinds & ~15) != 0 || kinds < 0 || (categories & ~4095) != 0 || categories < 0
            || from < 0 || until < 0 || (from > 0 && until > 0 && from >= until)) {
            throw new IllegalArgumentException("Invalid export selection");
        }
        ZoneId.of(zone);
    }
    private static String tokens(int mask, String[] names) {
        if (mask == 0) return "All";
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < names.length; i++) if ((mask & (1 << i)) != 0) selected.add(names[i]);
        return String.join("+", selected);
    }
    private static String day(long time, ZoneId zone) {
        return time <= 0 ? "unknown" : DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone).format(Instant.ofEpochMilli(time));
    }
    public static String name(long requestedAt, int kinds, int categories, long from, long until,
                              boolean backupTime, String zoneId, long coveredFrom, long coveredUntil,
                              int count, long bytes, String id) {
        validate(kinds, categories, from, until, zoneId);
        if (requestedAt <= 0 || count < 0 || bytes < 0 || !id.replace("-", "").matches("[a-fA-F0-9]{8,64}")) throw new IllegalArgumentException("Invalid export summary");
        ZoneId zone = ZoneId.of(zoneId);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(requestedAt));
        boolean all = from == 0 && until == 0;
        String range = all ? "all-" + day(coveredFrom, zone) + "-" + day(coveredUntil, zone)
            : (from == 0 ? "start" : day(from, zone)) + "-" + (until == 0 ? "latest" : day(until - 1, zone));
        return "TG-Photos_Cloud_" + stamp + "_" + tokens(kinds, new String[]{"Photos", "Videos", "Audio", "Files"})
            + "_" + tokens(categories, new String[]{"Cam", "Docs", "Shots", "ScreenRec", "IG", "WA", "TG", "FB", "Downloads", "PNG", "Other", "Movies"})
            + "_" + (backupTime ? "backup-" : "original-") + range + "_" + count + "files_" + bytes + "B_" + id.replace("-", "").substring(0, 8) + ".zip";
    }
}
