package app.televault.domain;
import java.nio.*;
/** Native decoder output range and WAV bounds. No sample padding or invented duration. */
public final class AudioCut {
 private AudioCut(){}
 public static int frame(long targetUs,long presentationUs,int rate,int frames) {
  if(rate<8000 || rate>192000 || frames<0)throw new IllegalArgumentException("Invalid PCM bounds");
  if(targetUs<=presentationUs)return 0;
  long delta=Math.subtractExact(targetUs,presentationUs);
  long span=(long)frames*1000000/rate+1;
  if(delta>=span)return frames;
  return (int)Math.min(frames,(delta*rate+999999)/1000000);
 }
 public static byte[] header(long bytes,int rate,int channels) {
  if(bytes<0 || bytes>0xffffffffL-36 || rate<8000 || rate>192000 || channels<1 || channels>8 || bytes%(channels*2)!=0)throw new IllegalArgumentException("Invalid WAV bounds");
  return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int)(bytes+36)).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2).putShort((short)(channels*2)).putShort((short)16).put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int)bytes).array();
 }
}
