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
                result.add(new Highlight("anniversary:" + best, years, best, spread(dates.get(best))));
            }
        }
        List<LocalDate> gatherings = dates.entrySet().stream().filter(e -> e.getValue().size() >= 3 && !used.contains(e.getKey())).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList());
        if (!gatherings.isEmpty() && result.size() < 5) {
            LocalDate date = gatherings.get((int) Math.floorMod(today.toEpochDay(), (long) gatherings.size()));
            result.add(new Highlight("day:" + date, 0, date, spread(dates.get(date))));
        }
        // A recent real day is useful even for a new library without year-old photos.
        Map<LocalDate, LinkedHashSet<String>> recent = new TreeMap<>(Comparator.reverseOrder());
        for (Photo photo : photos) {
            if (photo.time <= 0 || photo.id == null) continue;
            LocalDate date = Instant.ofEpochMilli(photo.time).atZone(zone).toLocalDate();
            if (!date.isAfter(today) && date.isAfter(today.minusDays(30))) recent.computeIfAbsent(date, ignored -> new LinkedHashSet<>()).add(photo.id);
        }
        for (var entry : recent.entrySet()) {
            if (entry.getValue().size() >= 3) {
                result.add(new Highlight("recent:" + entry.getKey(), 0, entry.getKey(), spread(entry.getValue())));
                break;
            }
        }
        return List.copyOf(result);
    }
    private static List<String> spread(LinkedHashSet<String> ids) {
        List<String> all = new ArrayList<>(ids);
        if (all.size() <= 30) return List.copyOf(all);
        List<String> selected = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) selected.add(all.get((int) ((long) i * (all.size() - 1) / 29)));
        return List.copyOf(selected);
    }
}
