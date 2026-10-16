package app.televault.crypto;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class OfflineCipherStoreTest {
    @Test public void retainedCipherSurvivesRestartAndNeverNeedsNetworkOnRead()throws Exception {
        File directory=Files.createTempDirectory("offline-cipher").toFile();
        try {
            byte[] key=VaultCrypto.randomKey();UUID vault=UUID.randomUUID(),object=UUID.randomUUID();byte[] plain=new byte[1700000];new Random(7).nextBytes(plain);
            ByteArrayOutputStream output=new ByteArrayOutputStream();VaultCrypto.encrypt(new ByteArrayInputStream(plain),output,key,vault,object,plain.length,bytes->{});
            byte[] cipher=output.toByteArray();OfflineCipherStore store=new OfflineCipherStore(directory);
            store.save("one/account/file",cipher.length,4*1024*1024,(position,count)->Arrays.copyOfRange(cipher,(int)position,(int)position+count),file->{
                try(RandomAccessFile source=new RandomAccessFile(file,"r");AuthenticatedRecords reader=new AuthenticatedRecords((at,buffer,start,count)->{source.seek(at);source.readFully(buffer,start,count);},key,vault,object,plain.length,cipher.length)) {
                    byte[] decoded=new byte[plain.length];assertEquals(decoded.length,reader.read(0,decoded,0,decoded.length));assertArrayEquals(plain,decoded);
                }
            });
            OfflineCipherStore reopened=new OfflineCipherStore(directory);assertTrue(reopened.contains("one/account/file",cipher.length));assertFalse(reopened.contains("another/account/file",cipher.length));
            assertArrayEquals(Arrays.copyOfRange(cipher,101,501),reopened.read("one/account/file",cipher.length,101,400));reopened.remove("one/account/file");assertNull(reopened.read("one/account/file",cipher.length,0,2));
        }finally{for(File file:directory.listFiles())file.delete();directory.delete();}
    }
    @Test public void shortTamperedAndOverQuotaDownloadsNeverCommit()throws Exception {
        File directory=Files.createTempDirectory("offline-failure").toFile();
        try {
            OfflineCipherStore store=new OfflineCipherStore(directory);
            try{store.save("x",10,9,(at,count)->new byte[count],file->{});fail();}catch(IOException expected){}
            try{store.save("x",10,100,(at,count)->new byte[count-1],file->{});fail();}catch(IOException expected){}
            try{store.save("x",10,100,(at,count)->new byte[count],file->{throw new IOException("Authentication failed");});fail();}catch(IOException expected){}
            assertEquals(0,store.bytes());assertEquals(0,directory.listFiles().length);
        }finally{directory.delete();}
    }
}
