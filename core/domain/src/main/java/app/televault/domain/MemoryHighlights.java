package app.televault.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Metadata-only, phone-local memories. Never infers a birthday, identity or private event. */
public final class MemoryHighlights {
    private MemoryHighlights() { }
    public record Photo(String id, long time) { }
    public record Highlight(String key, int yearsAgo, LocalDate date, List<String> ids) { }
    public static List<Highlight> select(List<Photo> photos, LocalDate today, ZoneId zone) {
        Map<LocalDate, LinkedHashSet<String>> dates = new TreeMap<>();
        for (Photo photo : photos) {
            if (photo.time <= 0 || photo.id == null) continue;
            LocalDate date = Instant.ofEpochMilli(photo.time).atZone(zone).toLocalDate();
            if (date.isAfter(today.minusDays(90))) continue;
            dates.computeIfAbsent(date, ignored -> new LinkedHashSet<>()).add(photo.id);
        }
        List<Highlight> result = new ArrayList<>();
        Set<LocalDate> used = new HashSet<>();
        for (int years = 1; years <= 20 && result.size() < 4; years++) {
            LocalDate anniversary = today.minusYears(years);
            LocalDate best = null;
            long distance = Long.MAX_VALUE;
            for (LocalDate date : dates.keySet()) {
                long delta = Math.abs(ChronoUnit.DAYS.between(anniversary, date));
                if (delta <= 7 && delta < distance) { best = date; distance = delta; }
            }
            if (best != null && used.add(best)) {
                result.add(new Highlight("anniversary:" + best, years, best, dates.get(best).stream().limit(30).collect(java.util.stream.Collectors.toList())));
            }
        }
        List<LocalDate> gatherings = dates.entrySet().stream().filter(e -> e.getValue().size() >= 3 && !used.contains(e.getKey())).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList());
        if (!gatherings.isEmpty() && result.size() < 5) {
            LocalDate date = gatherings.get((int) Math.floorMod(today.toEpochDay(), (long) gatherings.size()));
            result.add(new Highlight("day:" + date, 0, date, dates.get(date).stream().limit(30).collect(java.util.stream.Collectors.toList())));
        }
        return List.copyOf(result);
    }
}
