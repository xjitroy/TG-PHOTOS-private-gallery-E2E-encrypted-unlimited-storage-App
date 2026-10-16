package app.televault.crypto;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;

public class VaultCryptoTest {
    @Test public void roundtripAndContainerLength() throws Exception {
        for(int size:new int[]{0,1,1024,1048576,1048577,3*1048576+73}) {
            byte[] plain=new byte[size]; new Random(17).nextBytes(plain);
            byte[] key=VaultCrypto.randomKey(); UUID vault=UUID.randomUUID(), object=UUID.randomUUID();
            var encrypted=new ByteArrayOutputStream();
            byte[] hash=VaultCrypto.encrypt(new ByteArrayInputStream(plain),encrypted,key,vault,object,size,n->{});
            assertEquals(VaultCrypto.encryptedLength(size),encrypted.size());
            var restored=new ByteArrayOutputStream();
            VaultCrypto.decrypt(new ByteArrayInputStream(encrypted.toByteArray()),restored,key,vault,object,size,hash,n->{});
            assertArrayEquals(plain,restored.toByteArray());
        }
    }
    @Test public void tamperWrongKeyTruncationAndContextFail() throws Exception {
        byte[] plain=new byte[1024],key=VaultCrypto.randomKey(); UUID vault=UUID.randomUUID(),object=UUID.randomUUID();
        var bytes=new ByteArrayOutputStream(); byte[] hash=VaultCrypto.encrypt(new ByteArrayInputStream(plain),bytes,key,vault,object,plain.length,n->{});
        byte[] original=bytes.toByteArray();
        for(int offset:new int[]{0,5,21,37,41,49,57,61,original.length-1}) {
            byte[] altered=original.clone(); altered[offset]^=1;
            assertThrows(Exception.class,()->VaultCrypto.decrypt(new ByteArrayInputStream(altered),new ByteArrayOutputStream(),key,vault,object,plain.length,hash,n->{}));
        }
        assertThrows(GeneralSecurityException.class,()->VaultCrypto.decrypt(new ByteArrayInputStream(original),new ByteArrayOutputStream(),VaultCrypto.randomKey(),vault,object,plain.length,hash,n->{}));
        assertThrows(Exception.class,()->VaultCrypto.decrypt(new ByteArrayInputStream(Arrays.copyOf(original,original.length-1)),new ByteArrayOutputStream(),key,vault,object,plain.length,hash,n->{}));
        assertThrows(GeneralSecurityException.class,()->VaultCrypto.decrypt(new ByteArrayInputStream(original),new ByteArrayOutputStream(),key,UUID.randomUUID(),object,plain.length,hash,n->{}));
        byte[] wrongHash=hash.clone();wrongHash[0]^=1;
        assertThrows(GeneralSecurityException.class,()->VaultCrypto.decrypt(new ByteArrayInputStream(original),new ByteArrayOutputStream(),key,vault,object,plain.length,wrongHash,n->{}));
    }
    @Test public void recoveryAndMetadataIntegrity() throws Exception {
        byte[] salt=VaultCrypto.randomBytes(16),master=VaultCrypto.randomKey(),aad="root/v1".getBytes();
        byte[] derived=VaultCrypto.recoveryKey("a long recovery phrase".toCharArray(),salt);
        byte[] wrap=VaultCrypto.seal(derived,master,aad);
        assertArrayEquals(master,VaultCrypto.open(VaultCrypto.recoveryKey("a long recovery phrase".toCharArray(),salt),wrap,aad));
        byte[] wrong=VaultCrypto.recoveryKey("a different long phrase".toCharArray(),salt);
        assertThrows(GeneralSecurityException.class,()->VaultCrypto.open(wrong,wrap,aad));
        wrap[wrap.length-1]^=1;
        assertThrows(GeneralSecurityException.class,()->VaultCrypto.open(derived,wrap,aad));
    }
    @Test public void retriesUseDifferentCiphertextAndInputBounds() throws Exception {
        byte[] key=VaultCrypto.randomKey();UUID vault=UUID.randomUUID(),object=UUID.randomUUID();
        var first=new ByteArrayOutputStream();var second=new ByteArrayOutputStream();
        VaultCrypto.encrypt(new ByteArrayInputStream(new byte[1]),first,key,vault,object,1,n->{});
        VaultCrypto.encrypt(new ByteArrayInputStream(new byte[1]),second,key,vault,object,1,n->{});
        assertFalse(Arrays.equals(first.toByteArray(),second.toByteArray()));
        assertThrows(IllegalArgumentException.class,()->VaultCrypto.encryptedLength(-1));
        assertThrows(IllegalArgumentException.class,()->VaultCrypto.recoveryKey(new char[12],new byte[1]));
    }
}
