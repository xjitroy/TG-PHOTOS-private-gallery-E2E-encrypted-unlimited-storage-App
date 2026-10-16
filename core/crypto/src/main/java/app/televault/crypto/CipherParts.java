package app.televault.crypto;

import java.io.*;
import java.util.*;

/** Multipart ciphertext transport; record authentication remains VaultCrypto's responsibility. */
public final class CipherParts {
    private CipherParts() {}
    public static List<Long> lengths(long bytes, long cap) {
        if (bytes <= 0 || cap <= 0) throw new IllegalArgumentException("Invalid part bounds");
        long count = (bytes - 1) / cap + 1;
        if (count > 64) throw new IllegalArgumentException("Too many parts");
        List<Long> result = new ArrayList<>();
        while (bytes > 0) { long length = Math.min(bytes, cap); result.add(length); bytes -= length; }
        return List.copyOf(result);
    }
    public static void extract(File source, File target, long offset, long length, Runnable cancellation) throws IOException {
        if (offset < 0 || length <= 0 || offset > source.length() - length) throw new IOException("Invalid ciphertext range");
        try (var input = new RandomAccessFile(source, "r"); var output = new FileOutputStream(target)) {
            input.seek(offset); byte[] buffer = new byte[65536]; long remaining = length;
            while (remaining > 0) {
                cancellation.run(); int n = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (n <= 0) throw new EOFException(); output.write(buffer,0,n); remaining -= n;
            }
            output.getFD().sync();
        }
    }
    public static InputStream join(List<File> files) throws IOException {
        if (files.isEmpty() || files.size() > 64) throw new IOException("Invalid part count");
        // Open lazily: do not consume 64 descriptors for a single download.
        return new InputStream() {
            int index; InputStream current; boolean closed;
            private boolean ready() throws IOException {
                if (closed) throw new IOException("Closed");
                if (current == null && index < files.size()) current = new FileInputStream(files.get(index++));
                return current != null;
            }
            @Override public int read() throws IOException { byte[] one=new byte[1]; return read(one,0,1)<0 ? -1 : one[0]&255; }
            @Override public int read(byte[] b,int off,int len) throws IOException {
                Objects.checkFromIndexSize(off,len,b.length); if(len==0)return 0;
                while(ready()) { int n=current.read(b,off,len); if(n>=0)return n; current.close(); current=null; }
                return -1;
            }
            @Override public void close() throws IOException { closed=true; if(current!=null)current.close(); }
        };
    }
}
