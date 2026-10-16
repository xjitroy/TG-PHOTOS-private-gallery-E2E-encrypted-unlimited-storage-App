package app.televault.crypto;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class IntegrityFailureTest {
    @Test public void realCorruptRecordsInvalidateButTransientNetworkFailureKeepsOtherCachedFiles() throws Exception {
        var key = VaultCrypto.randomKey(); var vault = UUID.randomUUID(); var object = UUID.randomUUID();
        byte[] plain = new byte[VaultCrypto.RECORD_BYTES + 10]; Arrays.fill(plain, (byte) 31);
        var out = new ByteArrayOutputStream();
        VaultCrypto.encrypt(new ByteArrayInputStream(plain), out, key, vault, object, plain.length, n -> {});
        byte[] cipher = out.toByteArray();
        var input = (AuthenticatedRecords.CipherInput)(p,b,o,n) -> System.arraycopy(cipher, (int)p, b, o, n);
        cipher[cipher.length-1] ^= 1;
        try (var reader = new AuthenticatedRecords(input,key,vault,object,plain.length,cipher.length)) {
            byte[] returned = new byte[20]; Arrays.fill(returned, (byte) 17);
            var failure = assertThrows(IOException.class, () -> reader.read(VaultCrypto.RECORD_BYTES-10,returned,0,20));
            assertTrue(AuthenticatedRecords.integrityFailure(failure));
            for(int i=0;i<10;i++) assertEquals(0, returned[i]);
        }
        var network = (AuthenticatedRecords.CipherInput)(p,b,o,n) -> {
            if (p >= VaultCrypto.HEADER_BYTES + VaultCrypto.RECORD_BYTES + 20) throw new IOException("Network unavailable");
            input.readFully(p,b,o,n);
        };
        try (var reader = new AuthenticatedRecords(network,key,vault,object,plain.length,cipher.length)) {
            byte[] returned = new byte[20]; Arrays.fill(returned, (byte) 17);
            var failure = assertThrows(IOException.class, () -> reader.read(VaultCrypto.RECORD_BYTES-10,returned,0,20));
            assertFalse(AuthenticatedRecords.integrityFailure(failure));
            for(int i=0;i<10;i++) assertEquals(0, returned[i]);
            assertThrows(IOException.class, () -> reader.read(0,returned,0,1));
        }
    }
}
