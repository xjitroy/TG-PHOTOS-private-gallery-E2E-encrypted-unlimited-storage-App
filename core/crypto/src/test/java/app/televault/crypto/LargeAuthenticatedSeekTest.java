package app.televault.crypto;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.Test;

/** Virtual authenticated movies: only selected1MB records exist; no5/10GB allocation. */
public class LargeAuthenticatedSeekTest {
    @Test public void fiveAndTenGiBSeekAuthenticatesBoundedRecordsBeyondSignedIntOffsets() throws Exception {
        for (long length : new long[]{5L*1024*1024*1024+79,10L*1024*1024*1024+79}) {
            byte[] key = VaultCrypto.randomKey(), prefix = new byte[8];
            UUID vault = UUID.randomUUID(), object = UUID.randomUUID();
            byte[] header = ByteBuffer.allocate(VaultCrypto.HEADER_BYTES).put("TVLT1".getBytes(StandardCharsets.US_ASCII))
                .putLong(vault.getMostSignificantBits()).putLong(vault.getLeastSignificantBits())
                .putLong(object.getMostSignificantBits()).putLong(object.getLeastSignificantBits())
                .putInt(VaultCrypto.RECORD_BYTES).putLong(length).put(prefix).array();
            TreeMap<Long,byte[]> regions = new TreeMap<>();regions.put(0L, header);
            long last = (length-1)/VaultCrypto.RECORD_BYTES;
            for (long index : new long[]{0,2048,last}) {
                int count = (int)Math.min(VaultCrypto.RECORD_BYTES,length-index*VaultCrypto.RECORD_BYTES);
                byte[] plain = new byte[count];
                for (int i=0;i<count;i++) plain[i]=(byte)(index+i);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,ByteBuffer.allocate(12).put(prefix).putInt((int)index).array()));
                cipher.updateAAD(header);cipher.updateAAD(ByteBuffer.allocate(8).putInt((int)index).putInt(count).array());
                byte[] sealed=cipher.doFinal(plain);
                regions.put(VaultCrypto.HEADER_BYTES+index*(VaultCrypto.RECORD_BYTES+20L),ByteBuffer.allocate(4+sealed.length).putInt(count).put(sealed).array());
            }
            AtomicLong readBytes = new AtomicLong();
            try (var reader = new AuthenticatedRecords((position,target,offset,count)->{
                var region=regions.floorEntry(position);
                if(region==null || position-region.getKey()+count>region.getValue().length) throw new IOException("Unrequested record");
                assertTrue(count<=VaultCrypto.RECORD_BYTES+16);
                System.arraycopy(region.getValue(),Math.toIntExact(position-region.getKey()),target,offset,count);
                readBytes.addAndGet(count);
            },key,vault,object,length,VaultCrypto.encryptedLength(length))) {
                for(long position:new long[]{2L*1024*1024*1024+17,length-31,3}) {
                    byte[] result=new byte[31];assertEquals(31,reader.read(position,result,0,result.length));
                    long index=position/VaultCrypto.RECORD_BYTES;int within=(int)(position%VaultCrypto.RECORD_BYTES);
                    for(int i=0;i<result.length;i++) assertEquals((byte)(index+within+i),result[i]);
                }
                assertEquals(length,reader.length());
                assertEquals(-1,reader.read(length,new byte[1],0,1));
            }
            assertTrue(readBytes.get()<4L*VaultCrypto.RECORD_BYTES);
        }
    }
}
