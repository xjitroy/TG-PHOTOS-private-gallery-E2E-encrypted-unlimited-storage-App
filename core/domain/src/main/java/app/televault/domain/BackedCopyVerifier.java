package app.televault.domain;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** A completed cloud manifest is insufficient: verify the entire current original before cleanup. */
public final class BackedCopyVerifier {
    private BackedCopyVerifier() {}
    public static boolean verify(InputStream input, long size, byte[] expected, Runnable checkpoint) throws IOException {
        if (expected.length != 32) return false;
        byte[] actual = digest(input, size, checkpoint);
        return actual != null && MessageDigest.isEqual(actual, expected);
    }
    public static byte[] digest(InputStream input, long size, Runnable checkpoint) throws IOException {
        if (size < 0) return null;
        byte[] buffer = new byte[65536];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            while (true) {
                checkpoint.run();
                int limit = size-count >= buffer.length ? buffer.length : (int)(size-count+1);
                int read = input.read(buffer, 0, limit);
                if (read < 0) break;
                if (read == 0) continue;
                count += read;
                if (count > size) return null;
                digest.update(buffer, 0, read);
            }
            return count == size ? digest.digest() : null;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            Arrays.fill(buffer, (byte)0);
        }
    }
}
