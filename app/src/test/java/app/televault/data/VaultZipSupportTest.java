package app.televault.data;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class VaultZipSupportTest {
    private byte[] zip(String password, byte[] payload) throws IOException {
        var result = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(result, password.toCharArray())) {
            zip.putNextEntry(VaultZipSupport.entry("File-000001", payload.length));
            zip.write(payload); zip.closeEntry();
            byte[] index = "[{\"entry\":\"File-000001\",\"name\":\"my-private-photo.jpg\"}]".getBytes(StandardCharsets.UTF_8);
            zip.putNextEntry(VaultZipSupport.entry("index.json", index.length)); zip.write(index); zip.closeEntry();
        }
        return result.toByteArray();
    }
    @Test public void standardEncryptedZipRestoresAndHidesRealNames() throws Exception {
        String password = VaultZipSupport.password();
        byte[] payload = new byte[131087]; new java.util.Random(9).nextBytes(payload);
        byte[] expected = payload.clone(); byte[] archive = zip(password, payload);
        assertFalse(new String(archive, StandardCharsets.ISO_8859_1).contains("my-private-photo.jpg"));
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive), password.toCharArray())) {
            var header = input.getNextEntry(); assertTrue(header.isEncrypted()); assertEquals("File-000001", header.getFileName());
            assertArrayEquals(expected, input.readAllBytes());
            assertEquals("index.json", input.getNextEntry().getFileName());
            assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("my-private-photo.jpg"));
            assertNull(input.getNextEntry());
        }
    }
    @Test public void wrongPasswordAndChangedCiphertextAreRejected() throws Exception {
        String password = VaultZipSupport.password(); byte[] archive = zip(password, new byte[71]);
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive), VaultZipSupport.password().toCharArray())) {
            input.getNextEntry(); input.readAllBytes(); fail("Wrong ZIP password accepted");
        } catch(IOException expected) {}
        int name = (archive[26] & 255) | (archive[27] & 255) << 8;
        int extra = (archive[28] & 255) | (archive[29] & 255) << 8;
        archive[30 + name + extra + 16 + 2 + 7] ^= 1;
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive), password.toCharArray())) {
            input.getNextEntry(); input.readAllBytes(); fail("Tampered ZIP payload accepted");
        } catch(IOException expected) {}
    }
    @Test public void passwordsAreIndependentAndPathTraversalCannotBecomeEntry() {
        String first = VaultZipSupport.password(), second = VaultZipSupport.password();
        assertEquals(32, first.length()); assertNotEquals(first, second);
        for (String name : Arrays.asList("../password.txt", "/private/file", "File-000001/../../x")) {
            try { VaultZipSupport.entry(name, 1); fail("Unsafe ZIP path accepted"); } catch(IllegalArgumentException expected) {}
        }
    }
}
