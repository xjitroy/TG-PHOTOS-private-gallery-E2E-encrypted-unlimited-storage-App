package app.televault.domain;

/** Similarity is a suggestion, never an authentication or identity proof. */
public final class FaceSimilarity {
    private FaceSimilarity() { }
    public static float[] normalized(float[] input) {
        if (input == null || input.length != 128) throw new IllegalArgumentException("Invalid embedding");
        double sum = 0;
        for (float x : input) { if (!Float.isFinite(x)) throw new IllegalArgumentException("Invalid embedding"); sum += x*x; }
        if (sum < 1e-12) throw new IllegalArgumentException("Empty embedding");
        float[] result = input.clone(); double norm = Math.sqrt(sum);
        for (int i=0;i<result.length;i++) result[i] /= norm;
        return result;
    }
    public static float cosine(float[] left, float[] right) {
        float[] a = normalized(left), b = normalized(right); double dot = 0;
        try { for (int i=0;i<128;i++) dot += a[i]*b[i]; return (float)Math.max(-1,Math.min(1,dot)); }
        finally { java.util.Arrays.fill(a,0); java.util.Arrays.fill(b,0); }
    }
    public static boolean confident(float best, float second, int references) {
        return Float.isFinite(best) && Float.isFinite(second) && references > 0 && best >= .80f && best-second >= .12f;
    }
}
