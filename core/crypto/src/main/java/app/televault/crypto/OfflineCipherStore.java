package app.televault.crypto;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Explicit offline copies of already-encrypted bytes. Authentication is mandatory before commit. */
public final class OfflineCipherStore {
    public interface Fetch { byte[] read(long offset, int length) throws IOException; }
    public interface Verify { void verify(File cipher) throws IOException; }
    private final File directory;
    public OfflineCipherStore(File directory) {
        this.directory=directory;
        directory.mkdirs();
        File[] files=directory.listFiles();
        if(files!=null) for(File file:files) if(file.getName().endsWith(".partial")) file.delete();
    }
    private File file(String scope) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            StringBuilder name=new StringBuilder(); for(byte b:hash) name.append(String.format(Locale.ROOT,"%02x",b & 255));
            return new File(directory,name+".bin");
        } catch(NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
    public synchronized long bytes() {
        long total=0; File[] files=directory.listFiles();
        if(files!=null)for(File file:files)if(file.getName().endsWith(".bin"))total=Math.addExact(total,file.length());
        return total;
    }
    public boolean contains(String scope,long length) { File file=file(scope);return file.isFile() && file.length()==length; }
    public void save(String scope,long length,long quota,Fetch fetch,Verify verify)throws IOException {
        if(length<0 || length>quota || quota<0 || bytes()-file(scope).length()>quota-length)throw new IOException("Offline quota exceeded");
        if(directory.getUsableSpace()<length+16L*1024*1024)throw new IOException("Insufficient offline storage");
        File temporary=File.createTempFile("cipher-",".partial",directory);
        try {
            try(FileOutputStream output=new FileOutputStream(temporary)) {
                long position=0;
                while(position<length) {
                    int count=(int)Math.min(1024*1024,length-position);byte[] data=fetch.read(position,count);
                    try { if(data.length!=count)throw new IOException("Short offline copy");output.write(data);position+=count; }
                    finally {Arrays.fill(data,(byte)0);}
                }
                output.getFD().sync();
            }
            verify.verify(temporary);
            if(temporary.length()!=length)throw new IOException("Offline length changed");
            Files.move(temporary.toPath(),file(scope).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {temporary.delete();}
    }
    public byte[] read(String scope,long size,long offset,int count)throws IOException {
        if(offset<0 || count<0 || offset>size-count)throw new IOException("Invalid offline range");
        File file=file(scope);if(!file.isFile() || file.length()!=size)return null;
        byte[] result=new byte[count];
        try(RandomAccessFile input=new RandomAccessFile(file,"r")) {input.seek(offset);input.readFully(result);return result;}
        catch(IOException failure) {Arrays.fill(result,(byte)0);throw failure;}
    }
    public void remove(String scope)throws IOException {File file=file(scope);if(file.exists() && !file.delete())throw new IOException("Cannot remove offline copy");}
}
