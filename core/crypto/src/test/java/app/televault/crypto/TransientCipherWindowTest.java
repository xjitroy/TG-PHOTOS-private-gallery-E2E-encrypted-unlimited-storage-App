package app.televault.crypto;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransientCipherWindowTest {
    @Test public void coalescesHeaderAndRecordReadAndDiscardsOnClose() throws Exception {
        byte[] source = new byte[VaultCrypto.RECORD_BYTES * 2 + VaultCrypto.HEADER_BYTES + 40]; new Random(19).nextBytes(source);
        int[] calls = {0};
        var window = new TransientCipherWindow((position, bytes, offset, length) -> { calls[0]++; System.arraycopy(source, (int)position, bytes, offset, length); }, source.length);
        byte[] header = new byte[VaultCrypto.HEADER_BYTES], count = new byte[4], record = new byte[VaultCrypto.RECORD_BYTES + 16];
        window.readFully(0, header, 0, header.length); window.readFully(header.length, count, 0, 4); window.readFully(header.length + 4, record, 0, record.length);
        assertEquals(1, calls[0]); assertArrayEquals(Arrays.copyOfRange(source, header.length + 4, header.length + 4 + record.length), record);
        window.close();
        try { window.readFully(0, header, 0, header.length); fail("Closed stream reused"); } catch(IOException expected) {}
    }
    @Test public void boundaryReadAndNetworkFailureDoNotExposePartialBytes() throws Exception {
        int boundary = VaultCrypto.HEADER_BYTES + VaultCrypto.RECORD_BYTES + 20;
        byte[] source = new byte[boundary + 64]; Arrays.fill(source, (byte)17);
        var window = new TransientCipherWindow((position, bytes, offset, length) -> { if(position >= boundary) throw new IOException("Offline"); System.arraycopy(source, (int)position, bytes, offset, length); }, source.length);
        byte[] output = new byte[32];
        try { window.readFully(boundary - 16, output, 0, output.length); fail("Partial network read accepted"); } catch(IOException expected) {}
        assertArrayEquals(new byte[32], output);
    }
}
