package app.televault.domain;

/** Similarity is a suggestion, never an authentication or identity proof. */
public final class FaceSimilarity {
    private FaceSimilarity() { }
    public static float[] normalized(float[] input) {
        if (input == null || input.length != 128) throw new IllegalArgumentException("Invalid embedding");
        double sum = 0;
        for (float x : input) { if (!Float.isFinite(x)) throw new IllegalArgumentException("Invalid embedding"); sum += (double)x*x; }
        if (sum < 1e-12) throw new IllegalArgumentException("Empty embedding");
        float[] result = input.clone(); double norm = Math.sqrt(sum);
        for (int i=0;i<result.length;i++) result[i] /= norm;
        return result;
    }
    public static float cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != 128 || right.length != 128)
            throw new IllegalArgumentException("Invalid embedding");
        double dot = 0, normLeft = 0, normRight = 0;
        for (int i=0;i<128;i++) {
            double a = left[i], b = right[i];
            if (!Double.isFinite(a) || !Double.isFinite(b)) throw new IllegalArgumentException("Invalid embedding");
            dot += a*b; normLeft += a*a; normRight += b*b;
        }
        if (normLeft < 1e-12 || normRight < 1e-12) throw new IllegalArgumentException("Empty embedding");
        return (float)Math.max(-1, Math.min(1, dot / (Math.sqrt(normLeft) * Math.sqrt(normRight))));
    }
    public static boolean confident(float best, float second, int references) {
        return Float.isFinite(best) && Float.isFinite(second) && references > 0 && best >= (references >= 2 ? .50f : .55f) && best-second >= .06f;
    }
}
