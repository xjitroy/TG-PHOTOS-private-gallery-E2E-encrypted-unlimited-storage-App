package app.televault.crypto;

import java.io.IOException;
import java.util.Arrays;

/** One ciphertext record in stream RAM, coalescing TVLT header/tag reads. Never writes a file. */
public final class TransientCipherWindow implements AuthenticatedRecords.CipherInput, AutoCloseable {
    private final AuthenticatedRecords.CipherInput source;
    private final long length;
    private byte[] bytes;
    private long start = -1;
    private boolean closed;
    public TransientCipherWindow(AuthenticatedRecords.CipherInput source, long length) {
        if (length < VaultCrypto.HEADER_BYTES + 20L) throw new IllegalArgumentException("Invalid ciphertext length");
        this.source = source; this.length = length;
    }
    @Override public synchronized void readFully(long position, byte[] output, int offset, int count) throws IOException {
        if (closed || position < 0 || count < 0 || position > length - count || offset < 0 || offset > output.length - count) throw new IOException("Invalid ciphertext read");
        int done = 0;
        try {
            while (done < count) {
                long cursor = position + done;
                if (bytes == null || cursor < start || cursor >= start + bytes.length) {
                    if (bytes != null) Arrays.fill(bytes, (byte) 0);
                    bytes = null;
                    long stride = VaultCrypto.RECORD_BYTES + 20L;
                    long index = cursor < VaultCrypto.HEADER_BYTES ? 0 : (cursor - VaultCrypto.HEADER_BYTES) / stride;
                    start = index == 0 ? 0 : VaultCrypto.HEADER_BYTES + index * stride;
                    byte[] next = new byte[(int)Math.min(length - start, stride + (index == 0 ? VaultCrypto.HEADER_BYTES : 0))];
                    try { source.readFully(start, next, 0, next.length); bytes = next; }
                    catch (Throwable failure) { Arrays.fill(next, (byte) 0); throw failure; }
                }
                int within = Math.toIntExact(cursor - start);
                int take = Math.min(count - done, bytes.length - within);
                System.arraycopy(bytes, within, output, offset + done, take);
                done += take;
            }
        } catch (IOException | RuntimeException failure) {
            Arrays.fill(output, offset, offset + done, (byte) 0);
            close();
            throw failure;
        }
    }
    @Override public synchronized void close() { closed = true; if (bytes != null) Arrays.fill(bytes, (byte) 0); bytes = null; }
}
