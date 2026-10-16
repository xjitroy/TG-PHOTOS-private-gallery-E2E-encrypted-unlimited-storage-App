package app.televault.crypto;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NestedAuthenticatedRecordsTest {
    private final UUID photos = UUID.randomUUID(), vault = UUID.randomUUID(), object = UUID.randomUUID();
    private final byte[] a = VaultCrypto.randomKey(), b = VaultCrypto.randomKey();
    private byte[] encrypt(byte[] plain, byte[] key, UUID scope) throws Exception {
        var output = new ByteArrayOutputStream();
        VaultCrypto.encrypt(new ByteArrayInputStream(plain), output, key, scope, object, plain.length, ignored -> {});
        return output.toByteArray();
    }
    private NestedAuthenticatedRecords open(byte[] bytes, long size, byte[] inner, byte[] outer, UUID scope) throws IOException {
        return new NestedAuthenticatedRecords((position, output, offset, count) -> System.arraycopy(bytes, Math.toIntExact(position), output, offset, count), inner, outer, scope, vault, object, size, bytes.length);
    }
    @Test public void randomSeekAcrossBothRecordBoundariesAndEmptyFile() throws Exception {
        for (int size : new int[]{0, 37, VaultCrypto.RECORD_BYTES * 2 + 123}) {
            byte[] plain = new byte[size]; new Random(72).nextBytes(plain);
            byte[] cipher = encrypt(encrypt(plain, a, photos), b, vault);
            try (var reader = open(cipher, size, a, b, photos)) {
                for (int position : new int[]{0, Math.max(0, size / 2 - 9), Math.max(0, size - 100)}) {
                    int count = Math.min(100, size - position); byte[] read = new byte[count];
                    assertEquals(count == 0 ? 0 : count, reader.reader().read(position, read, 0, count));
                    assertArrayEquals(Arrays.copyOfRange(plain, position, position + count), read);
                }
            }
        }
    }
    @Test public void eitherMissingKeyOrWrongPhotosContextFailsBeforePlaintext() throws Exception {
        byte[] cipher = encrypt(encrypt(new byte[23], a, photos), b, vault);
        for (int attempt = 0; attempt < 3; attempt++) {
            try (var ignored = open(cipher, 23, attempt == 0 ? VaultCrypto.randomKey() : a, attempt == 1 ? VaultCrypto.randomKey() : b, attempt == 2 ? UUID.randomUUID() : photos)) {
                fail("Missing key/context accepted");
            } catch (IOException expected) {}
        }
    }
    @Test public void authenticatedOuterCannotHideTamperedInnerAndOuterTamperingFails() throws Exception {
        byte[] inner = encrypt(new byte[87], a, photos); inner[inner.length - 1] ^= 1;
        byte[] cipher = encrypt(inner, b, vault);
        try (var ignored = open(cipher, 87, a, b, photos)) { fail("Damaged inner accepted"); } catch (IOException expected) {}
        cipher = encrypt(encrypt(new byte[87], a, photos), b, vault); cipher[cipher.length - 1] ^= 1;
        try (var ignored = open(cipher, 87, a, b, photos)) { fail("Damaged outer accepted"); } catch (IOException expected) {}
    }
}
