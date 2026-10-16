package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
public class AudioCutTest {
 @Test public void trimsWholePcmFramesAtExactAndPartialTimes(){assertEquals(0,AudioCut.frame(0,1000000,48000,48000));assertEquals(24000,AudioCut.frame(1500000,1000000,48000,48000));assertEquals(1,AudioCut.frame(1000001,1000000,48000,48000));assertEquals(48000,AudioCut.frame(3000000,1000000,48000,48000));}
 @Test public void headerIsLittleEndianPcmWithExactDataLengthAndSafeBounds(){byte[] h=AudioCut.header(192000,48000,2);assertEquals(44,h.length);ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);assertEquals(192036,b.getInt(4));assertEquals(48000,b.getInt(24));assertEquals(192000,b.getInt(40));assertThrows(IllegalArgumentException.class,()->AudioCut.header(3,48000,2));assertThrows(IllegalArgumentException.class,()->AudioCut.header(0xffffffffL,48000,2));}
}
