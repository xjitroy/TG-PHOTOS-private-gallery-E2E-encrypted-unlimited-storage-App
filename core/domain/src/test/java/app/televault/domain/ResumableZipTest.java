package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.zip.*;

public class ResumableZipTest {
    private static ResumableZip.Entry append(ResumableZip zip,String name,byte[] bytes) throws Exception {
        long at=zip.begin(name,bytes.length,1800000000123L);zip.write(bytes,bytes.length);CRC32 crc=new CRC32();crc.update(bytes);
        return zip.commit(name,bytes.length,1800000000123L,at,crc.getValue());
    }
    @Test public void interruptedEntryIsDiscardedAndCompletedEntriesRemainReadable() throws Exception {
        Path path=Files.createTempFile("resumable-zip", ".zip");
        try {
            ResumableZip.Entry first;
            try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)) {
                var writer=new ResumableZip(channel,0);first=append(writer,"001/বাংলা.jpg",new byte[]{1,2,3});
                writer.begin("unfinished",99,0);writer.write(new byte[]{4,5},2);
            }
            try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)) {
                var writer=new ResumableZip(channel,first.end());var second=append(writer,"002/empty",new byte[]{});writer.finish(List.of(first,second));
            }
            try(var zip=new ZipFile(path.toFile())) {
                assertEquals(2,zip.size());assertArrayEquals(new byte[]{1,2,3},zip.getInputStream(zip.getEntry("001/বাংলা.jpg")).readAllBytes());
            }
            try(var zip=new ZipInputStream(Files.newInputStream(path))) {
                assertEquals("001/বাংলা.jpg",zip.getNextEntry().getName());assertArrayEquals(new byte[]{1,2,3},zip.readAllBytes());
                assertEquals("002/empty",zip.getNextEntry().getName());assertEquals(0,zip.readAllBytes().length);assertNull(zip.getNextEntry());
            }
        } finally { Files.deleteIfExists(path); }
    }
    @Test public void changedDestinationAndIncompleteEntryCannotBeCommitted() throws Exception {
        Path path=Files.createTempFile("zip-bounds", ".zip");
        try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)) {
            assertThrows(java.io.IOException.class,()->new ResumableZip(channel,5));var writer=new ResumableZip(channel,0);
            assertThrows(java.io.IOException.class,()->writer.begin("../outside",1,0));long at=writer.begin("short",10,0);writer.write(new byte[]{1},1);
            assertThrows(java.io.IOException.class,()->writer.commit("short",10,0,at,0));
        } finally { Files.deleteIfExists(path); }
        assertEquals(".._.._a.jpg",ResumableZip.safeName("../../a.jpg"));
        assertEquals("File",ResumableZip.safeName(".."));
    }
}
