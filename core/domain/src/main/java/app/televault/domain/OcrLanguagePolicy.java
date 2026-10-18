package app.televault.domain;

import java.util.List;

/** Bound simultaneous native models; try fallback scripts sequentially, not eight at once. */
public final class OcrLanguagePolicy {
    private OcrLanguagePolicy() { }
    public static List<String> candidates(String script, boolean thorough) {
        String primary = switch (script == null ? "" : script) {
            case "Bengali" -> "ben+hin+eng";
            case "Devanagari" -> "hin+mar+eng";
            case "Tamil" -> "tam+eng";
            case "Telugu" -> "tel+eng";
            case "Arabic" -> "ara+eng";
            case "Latin" -> "eng+spa";
            default -> "ben+hin+eng";
        };
        if (!thorough) {
            if (primary.equals("eng+spa")) return List.of(primary, "ben+hin+eng");
            if (script == null || script.isBlank() || primary.equals("ben+hin+eng") && !"Bengali".equals(script)) return List.of(primary, "eng+spa");
            return List.of(primary);
        }
        var result = new java.util.ArrayList<String>();
        result.add(primary);
        for (String group : List.of("ben+hin+eng", "hin+mar+eng", "eng+spa", "tam+eng", "tel+eng", "ara+eng"))
            if (!result.contains(group)) result.add(group);
        return List.copyOf(result);
    }
}
