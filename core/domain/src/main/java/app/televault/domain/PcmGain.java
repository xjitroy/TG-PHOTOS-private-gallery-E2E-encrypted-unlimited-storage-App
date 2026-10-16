package app.televault.domain;

/** Bounded signed little-endian PCM16 gain and sample-time fades, independent of decoder buffers. */
public final class PcmGain {
    private PcmGain() {}
    public static void apply(byte[] bytes, int channels, int rate, long firstUs, long startUs, long endUs,
                             float gain, long fadeInUs, long fadeOutUs) {
        if(channels<1 || channels>8 || rate<8000 || rate>192000 || bytes.length%(channels*2)!=0 ||
           !Float.isFinite(gain) || gain<0 || gain>2 || fadeInUs<0 || fadeOutUs<0 || endUs<=startUs)
            throw new IllegalArgumentException("Invalid PCM adjustment");
        int frames=bytes.length/(channels*2);
        for(int frame=0;frame<frames;frame++) {
            long time=firstUs+(long)frame*1_000_000/rate;
            float fade=1f;
            if(fadeInUs>0)fade=Math.min(fade,Math.max(0f,(float)(time-startUs)/fadeInUs));
            if(fadeOutUs>0)fade=Math.min(fade,Math.max(0f,(float)(endUs-time)/fadeOutUs));
            for(int channel=0;channel<channels;channel++) {
                int offset=(frame*channels+channel)*2;
                short sample=(short)((bytes[offset]&255)|(bytes[offset+1]<<8));
                int value=Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(sample*gain*fade)));
                bytes[offset]=(byte)value;bytes[offset+1]=(byte)(value>>>8);
            }
        }
    }
}
