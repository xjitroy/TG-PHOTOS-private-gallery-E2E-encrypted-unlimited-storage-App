package app.televault.domain;

import java.util.List;

/** Bound simultaneous native models; try fallback scripts sequentially, not eight at once. */
public final class OcrLanguagePolicy {
    private OcrLanguagePolicy() { }
    public static List<String> candidates(String script, boolean thorough) {
        String primary = switch (script == null ? "" : script) {
            case "Bengali" -> "ben+eng";
            case "Devanagari" -> "hin+mar+eng";
            case "Tamil" -> "tam+eng";
            case "Telugu" -> "tel+eng";
            case "Arabic" -> "ara+eng";
            case "Latin" -> "eng+spa";
            default -> "ben+hin+eng";
        };
        if (!thorough) return List.of(primary);
        var result = new java.util.ArrayList<String>();
        result.add(primary);
        for (String group : List.of("ben+hin+eng", "hin+mar+eng", "eng+spa", "tam+eng", "tel+eng", "ara+eng"))
            if (!result.contains(group)) result.add(group);
        return List.copyOf(result);
    }
}
