package app.televault.crypto;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class CipherPartsTest {
    @Test public void ciphertextPartsRestoreAndRejectReorderOrMissingPart() throws Exception {
        var dir=Files.createTempDirectory("cipher-parts");
        try {
            byte[] original=new byte[2*1024*1024+71];new Random(29).nextBytes(original);
            byte[] key=VaultCrypto.randomKey();var vault=UUID.randomUUID();var object=UUID.randomUUID();
            File encrypted=dir.resolve("whole.bin").toFile();byte[] hash;
            try(var out=new FileOutputStream(encrypted)){hash=VaultCrypto.encrypt(new ByteArrayInputStream(original),out,key,vault,object,original.length,n->{});}
            var parts=new ArrayList<File>();long offset=0;
            for(long length:CipherParts.lengths(encrypted.length(),300001)) {
                File part=dir.resolve("part-"+parts.size()).toFile();CipherParts.extract(encrypted,part,offset,length,()->{});parts.add(part);offset+=length;
            }
            var output=new ByteArrayOutputStream();
            try(var input=CipherParts.join(parts)){VaultCrypto.decrypt(input,output,key,vault,object,original.length,hash,n->{});}
            assertArrayEquals(original,output.toByteArray());
            Collections.swap(parts,0,1);
            assertThrows(Exception.class,()->{try(var in=CipherParts.join(parts)){VaultCrypto.decrypt(in,new ByteArrayOutputStream(),key,vault,object,original.length,hash,n->{});}});
            Collections.swap(parts,0,1);parts.remove(1);
            assertThrows(Exception.class,()->{try(var in=CipherParts.join(parts)){VaultCrypto.decrypt(in,new ByteArrayOutputStream(),key,vault,object,original.length,hash,n->{});}});
        } finally {try(var files=Files.list(dir)){files.forEach(p->p.toFile().delete());}dir.toFile().delete();}
    }
    @Test public void lengthAndBoundsDoNotOverflow() {
        assertEquals(List.of(1536L*1024*1024,1L),CipherParts.lengths(1536L*1024*1024+1,1536L*1024*1024));
        assertThrows(IllegalArgumentException.class,()->CipherParts.lengths(Long.MAX_VALUE,1));
        assertThrows(IllegalArgumentException.class,()->CipherParts.lengths(0,1));
        assertTrue(VaultCrypto.encryptedLength(8L*1024*1024*1024)>8L*1024*1024*1024);
        assertThrows(IllegalArgumentException.class,()->VaultCrypto.encryptedLength(64L*1024*1024*1024+1));
    }
}
