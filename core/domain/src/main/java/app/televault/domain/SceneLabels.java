package app.televault.domain;

import java.util.*;

/** Conservative ImageNet clues, never personal attributes, relationships or events. */
public final class SceneLabels {
    private SceneLabels() { }
    public static String select(float[] logits, List<String> labels) {
        if (logits.length != 1000 || labels.size() != 1000) return "";
        int best = 0;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < logits.length; i++) {
            if (!Float.isFinite(logits[i])) return "";
            if (logits[i] > max) { max = logits[i]; best = i; }
        }
        double sum = 0, rival = 0;
        for (int i = 0; i < logits.length; i++) {
            double score = Math.exp(logits[i] - max);
            sum += score;
            if (i != best) rival = Math.max(rival, score);
        }
        if (1 / sum < .35 || (1 - rival) / sum < .05) return "";
        String label = labels.get(best).replaceFirst("^n[0-9]+\\s+", "").toLowerCase(Locale.ROOT);
        String aliases = best >= 151 && best <= 268 ? " dog pet animal কুকুর पालतू कुत्ता" : best >= 281 && best <= 285 ? " cat pet animal বিড়াল बिल्ली" : "";
        if (best >= 924 && best <= 969) aliases += " food খাবার भोजन";
        if (theme(label).equals("Beach days")) aliases += " beach seaside সৈকত সমুদ্রসৈকত समुद्र तट";
        return label + aliases + " " + theme(label + aliases);
    }
    public static String theme(String label) {
        String value = label.toLowerCase(Locale.ROOT);
        if (value.contains(" pet animal")) return "Pet moments";
        if (value.contains("seashore") || value.contains("sandbar") || value.contains("coral reef")) return "Beach days";
        if (value.contains("garden") || value.contains("daisy") || value.contains("sunflower")) return "In bloom";
        if (value.contains("mountain") || value.contains("alp,") || value.contains("valley")) return "Great outdoors";
        if (value.contains("snow") || value.contains("ski ")) return "Winter moments";
        if (value.contains("palace") || value.contains("castle") || value.contains("monastery")) return "Places to remember";
        if (value.contains(" food ")) return "Food moments";
        if (value.contains("pizza") || value.contains("ice cream") || value.contains("cheeseburger") || value.contains("restaurant")) return "Food moments";
        return "";
    }
}
