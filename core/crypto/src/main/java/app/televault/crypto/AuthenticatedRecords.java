package app.televault.crypto;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Seekable TVLT1 playback. Every returned record is authenticated before any byte is exposed. */
public final class AuthenticatedRecords implements AutoCloseable {
    public static final class IntegrityException extends IOException {
        public IntegrityException(String message) { super(message); }
        public IntegrityException(String message, Throwable cause) { super(message, cause); }
    }
    public static boolean integrityFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) if (failure instanceof IntegrityException) return true;
        return false;
    }
    public interface CipherInput { void readFully(long position, byte[] output, int offset, int length) throws IOException; }
    private final CipherInput input;
    private final byte[] key, header, prefix;
    private final long length;
    private byte[] plain;
    private long loaded = -1;
    private boolean closed;
    public AuthenticatedRecords(CipherInput input, byte[] key, UUID vault, UUID object, long length, long cipherLength) throws IOException {
        if (key.length != 32 || cipherLength != VaultCrypto.encryptedLength(length)) throw new IOException("Invalid manifest bounds");
        this.input = input; this.key = key.clone(); this.length = length;
        this.header = new byte[VaultCrypto.HEADER_BYTES]; this.prefix = new byte[8];
        try {
            input.readFully(0, header, 0, header.length);
            var fields = ByteBuffer.wrap(header); byte[] magic = new byte[5]; fields.get(magic);
            var actualVault = new UUID(fields.getLong(), fields.getLong()); var actualObject = new UUID(fields.getLong(), fields.getLong());
            int size = fields.getInt(); long actualLength = fields.getLong(); fields.get(prefix);
            if (!Arrays.equals(magic, "TVLT1".getBytes(StandardCharsets.US_ASCII)) || !actualVault.equals(vault) || !actualObject.equals(object) || size != VaultCrypto.RECORD_BYTES || actualLength != length) throw new IntegrityException("Invalid authenticated context");
            load(0); // Includes the empty-file authentication tag; rejects a modified header on open.
        } catch(Throwable e) { close(); if(e instanceof IOException)throw (IOException)e; throw new IOException("Authentication failed",e); }
    }
    public long length() { return length; }
    private void load(long index) throws IOException {
        if (closed) throw new IOException("Reader closed");
        if (loaded == index) return;
        if (plain != null) { Arrays.fill(plain, (byte)0); plain = null; }
        long position = VaultCrypto.HEADER_BYTES + index * (VaultCrypto.RECORD_BYTES + 20L);
        int expected = (int)Math.min(VaultCrypto.RECORD_BYTES, length - index * VaultCrypto.RECORD_BYTES);
        if (expected < 0) throw new IOException("Record outside file");
        byte[] count = new byte[4]; input.readFully(position, count, 0, 4);
        if (ByteBuffer.wrap(count).getInt() != expected) throw new IntegrityException("Invalid record boundary");
        byte[] sealed = new byte[expected+16]; input.readFully(position+4, sealed, 0, sealed.length);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = ByteBuffer.allocate(12).put(prefix).putInt(Math.toIntExact(index)).array();
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,nonce));
            cipher.updateAAD(header); cipher.updateAAD(ByteBuffer.allocate(8).putInt(Math.toIntExact(index)).putInt(expected).array());
            plain = cipher.doFinal(sealed); loaded = index;
        } catch(GeneralSecurityException e) { throw new IntegrityException("Record authentication failed", e); }
        finally { Arrays.fill(sealed,(byte)0); }
    }
    public synchronized int read(long position, byte[] output, int offset, int count) throws IOException {
        if (offset < 0 || count < 0 || offset > output.length-count) throw new IndexOutOfBoundsException("Invalid buffer bounds");
        if (closed || position < 0 || position > length) throw new IOException("Invalid read");
        if (count == 0) return 0;
        if (position == length) return -1;
        int done = 0;
        try {
            while (done < count && position < length) {
                long index = position / VaultCrypto.RECORD_BYTES; load(index);
                int within = (int)(position % VaultCrypto.RECORD_BYTES);
                int take = Math.min(count-done, plain.length-within);
                System.arraycopy(plain,within,output,offset+done,take); done+=take;position+=take;
            }
            return done;
        } catch (IOException | RuntimeException e) {
            // A later damaged record must not leave a partial successful read in the caller buffer.
            Arrays.fill(output, offset, offset + done, (byte)0);
            close();
            throw e;
        }
    }
    @Override public synchronized void close() { closed=true;Arrays.fill(key,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);plain=null; }
}
