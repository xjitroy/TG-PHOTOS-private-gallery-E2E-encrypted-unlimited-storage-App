package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
public class PcmGainTest {
    private byte[] pcm(short... values) { var b=ByteBuffer.allocate(values.length*2).order(ByteOrder.LITTLE_ENDIAN); for(short v:values)b.putShort(v);return b.array(); }
    @Test public void clipsGainAndPreservesStereoSigns() {
        var data=pcm((short)20000,(short)-20000);PcmGain.apply(data,2,8000,500000,0,1000000,2f,0,0);
        var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);assertEquals(32767,b.getShort());assertEquals(-32768,b.getShort());
    }
    @Test public void fadeUsesAbsoluteTimeAcrossDecoderBuffers() {
        var data=pcm((short)10000,(short)-10000);PcmGain.apply(data,2,8000,250000,0,1000000,1f,500000,0);
        var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);assertEquals(5000,b.getShort());assertEquals(-5000,b.getShort());
        data=pcm((short)10000);PcmGain.apply(data,1,8000,900000,0,1000000,1f,0,200000);assertEquals(5000,ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMisalignedFrames() { PcmGain.apply(new byte[3],2,8000,0,0,1000000,1f,0,0); }
}
