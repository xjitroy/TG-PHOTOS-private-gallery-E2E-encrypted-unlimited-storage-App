package app.televault.domain;

/** The first100percent uses system volume; the next100percent is bounded PCM gain. */
public final class VideoVolume {
    private VideoVolume() { }
    public static int percent(int requested) { return Math.max(0, Math.min(200, requested)); }
    public static float gain(int percent) { return Math.max(1f, percent(percent) / 100f); }
    public static float amplify(float sample, float gain) {
        if (!Float.isFinite(sample)) return 0;
        if (!Float.isFinite(gain) || gain <= 1f) return sample;
        float value = sample * Math.min(gain, 2f);
        float magnitude = Math.abs(value);
        if (magnitude <= .85f) return value;
        float excess = magnitude - .85f;
        return Math.copySign(.85f + .15f * excess / (excess + .15f), value);
    }
}
