package app.televault.domain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Stored ZIP64, bounded streaming, caller-authenticated checkpoints at complete entry boundaries.
 * The destination must be seekable. No plaintext staging files and no compression of media. */
public final class ResumableZip {
    public record Entry(String name, long size, long time, long offset, long crc, long end) {}
    private final FileChannel channel;
    public ResumableZip(FileChannel channel, long checkpoint) throws IOException {
        if (checkpoint < 0 || channel.size() < checkpoint) throw new IOException("Export destination changed");
        this.channel=channel;
        channel.truncate(checkpoint);channel.position(checkpoint);
    }
    public static String safeName(String name) {
        String value=name==null?"File":name.replaceAll("[\\\\/\\x00-\\x1f]","_").trim();
        if(value.isEmpty() || value.equals(".") || value.equals("..")) value="File";
        return value.substring(0,Math.min(value.length(),180));
    }
    private void bytes(byte[] bytes) throws IOException { ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer); }
    private void fields(int size, java.util.function.Consumer<ByteBuffer> fill) throws IOException {
        ByteBuffer buffer=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);fill.accept(buffer);bytes(buffer.array());
    }
    private static int stamp(long time) {
        var value=Instant.ofEpochMilli(Math.max(0,time)).atZone(ZoneId.systemDefault());
        int year=Math.max(1980,Math.min(2107,value.getYear()));
        return ((year-1980)<<25)|(value.getMonthValue()<<21)|(value.getDayOfMonth()<<16)|(value.getHour()<<11)|(value.getMinute()<<5)|(value.getSecond()/2);
    }
    public long begin(String name,long size,long time) throws IOException {
        if(size<0 || name.startsWith("/") || name.contains("\\") || name.contains("../")) throw new IOException("Invalid ZIP entry");
        byte[] encoded=name.getBytes(StandardCharsets.UTF_8);
        if(encoded.length>65535) throw new IOException("ZIP name too long");
        long offset=channel.position();int dos=stamp(time);
        fields(30,b->b.putInt(0x04034b50).putShort((short)45).putShort((short)0x0800).putShort((short)0).putInt(dos).putInt(0).putInt(-1).putInt(-1).putShort((short)encoded.length).putShort((short)20));
        bytes(encoded);fields(20,b->b.putShort((short)1).putShort((short)16).putLong(size).putLong(size));
        return offset;
    }
    public void write(byte[] bytes,int count) throws IOException { ByteBuffer buffer=ByteBuffer.wrap(bytes,0,count);while(buffer.hasRemaining())channel.write(buffer); }
    public Entry commit(String name,long size,long time,long offset,long crc) throws IOException {
        long end=channel.position();
        long expected=offset+30+name.getBytes(StandardCharsets.UTF_8).length+20+size;
        if(end!=expected || crc<0 || crc>0xffffffffL) throw new IOException("Incomplete ZIP entry");
        channel.position(offset+14);fields(4,b->b.putInt((int)crc));channel.position(end);channel.force(true);
        return new Entry(name,size,time,offset,crc,end);
    }
    public void finish(List<Entry> entries) throws IOException { finish(entries,entries.size()); }
    public void finish(Iterable<Entry> entries,long count) throws IOException {
        long start=channel.position(), seen=0;
        if(count<0)throw new IOException("Invalid ZIP count");
        for(Entry e:entries) {
            seen++;
            byte[] name=e.name().getBytes(StandardCharsets.UTF_8);int dos=stamp(e.time());
            fields(46,b->b.putInt(0x02014b50).putShort((short)45).putShort((short)45).putShort((short)0x0800).putShort((short)0).putInt(dos).putInt((int)e.crc()).putInt(-1).putInt(-1).putShort((short)name.length).putShort((short)28).putShort((short)0).putShort((short)0).putShort((short)0).putInt(0).putInt(-1));
            bytes(name);fields(28,b->b.putShort((short)1).putShort((short)24).putLong(e.size()).putLong(e.size()).putLong(e.offset()));
        }
        if(seen!=count)throw new IOException("ZIP count changed");
        long length=channel.position()-start, zip64=channel.position();
        fields(56,b->b.putInt(0x06064b50).putLong(44).putShort((short)45).putShort((short)45).putInt(0).putInt(0).putLong(count).putLong(count).putLong(length).putLong(start));
        fields(20,b->b.putInt(0x07064b50).putInt(0).putLong(zip64).putInt(1));
        fields(22,b->b.putInt(0x06054b50).putShort((short)0).putShort((short)0).putShort((short)0xffff).putShort((short)0xffff).putInt(-1).putInt(-1).putShort((short)0));
        channel.force(true);
    }
}
