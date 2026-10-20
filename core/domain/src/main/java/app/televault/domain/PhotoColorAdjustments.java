package app.televault.domain;

/** Android-independent color transform; alpha is preserved and parameters are bounded. */
public final class PhotoColorAdjustments {
    private PhotoColorAdjustments() {}
    public static float[] matrix(float exposure, float saturation, float contrast, float warmth) {
        if (!Float.isFinite(exposure) || !Float.isFinite(saturation) || !Float.isFinite(contrast) || !Float.isFinite(warmth)
            || exposure < -1 || exposure > 1 || saturation < 0 || saturation > 2 || contrast < 0 || contrast > 2 || warmth < -1 || warmth > 1) throw new IllegalArgumentException("Invalid color adjustment");
        float inverse = 1 - saturation;
        float r = .213f * inverse, g = .715f * inverse, b = .072f * inverse;
        float[] result = {r + saturation,g,b,0,0, r,g + saturation,b,0,0, r,g,b + saturation,0,0, 0,0,0,1,0};
        float shift = (1 - contrast) * 127.5f + exposure * 100;
        float[] temperature = {1 + warmth * .25f, 1, 1 - warmth * .25f};
        for (int channel = 0; channel < 3; channel++) {
            for (int column = 0; column < 3; column++) result[channel * 5 + column] *= contrast * temperature[channel];
            result[channel * 5 + 4] = shift * temperature[channel];
        }
        return result;
    }
}
