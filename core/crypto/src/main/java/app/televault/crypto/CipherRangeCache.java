package app.televault.crypto;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Disposable original ciphertext only. Adaptive file deadlines and time-windowed video, bounded LRU. */
public final class CipherRangeCache {
    public interface Fetch { byte[] read(long offset,int length)throws IOException; }
    public static final long TTL=24*60*60*1000L;
    public static final long PHOTO_TTL=7*TTL;
    private static final int BLOCK=1024*1024;
    private static final long FREE_RESERVE=128L*1024*1024;
    private static final Pattern BLOCK_NAME=Pattern.compile("[0-9a-f]{64}(?:-photo)?\\.bin");
    private static final Pattern AGE_NAME=Pattern.compile("[0-9a-f]{64}(?:-photo)?\\.age");
    private static final Pattern POLICY_NAME=Pattern.compile("policy-[0-9a-f]{64}\\.life");
    private final Map<String,Long> policies=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Long> playbackWindows=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Long> windowPruned=new java.util.concurrent.ConcurrentHashMap<>();
    private final File directory;
    private final LongSupplier clock;
    private final Map<String,Gate> gates=new HashMap<>();
    private final Set<File> activeTemps=new HashSet<>();
    private long cap,generation,cachedBytes,pendingBytes;
    private long lastPrune=Long.MIN_VALUE;
    private static final class Gate { int users; }

    public CipherRangeCache(File directory,long cap,LongSupplier clock) {
        if(cap<BLOCK)throw new IllegalArgumentException("Invalid cache capacity");
        this.directory=Objects.requireNonNull(directory);this.cap=cap;this.clock=Objects.requireNonNull(clock);
    }
    public synchronized void setCapacity(long bytes) {
        if(bytes<BLOCK)throw new IllegalArgumentException("Invalid cache capacity");
        cap=bytes;prune();
    }
    public synchronized long bytes() { return cachedBytes; }
    private synchronized Gate acquire(String name) {
        Gate gate=gates.computeIfAbsent(name,ignored->new Gate());gate.users++;return gate;
    }
    private synchronized void release(String name,Gate gate) {
        if(--gate.users==0)gates.remove(name,gate);
        // A quota change can temporarily leave an active block above the limit. Reclaim as
        // soon as its last reader leaves, rather than interrupting playback mid-read.
        if(cachedBytes>cap)prune();
    }
    private synchronized void checkGeneration(long epoch)throws IOException {
        if(generation!=epoch)throw new IOException("Cache cleared during read");
    }
    private String name(String scope,long block) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((scope+"/"+block).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(64);
            for(byte b:digest)hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            return hex.toString();
        }catch(NoSuchAlgorithmException failure){throw new AssertionError(failure);}
    }
    public byte[] read(String scope,long size,long offset,int length,boolean keep,Fetch fetch)throws IOException {
        return read(scope,size,offset,length,keep,false,fetch);
    }
    public byte[] read(String scope,long size,long offset,int length,boolean keep,boolean photo,Fetch fetch)throws IOException {
        return read(scope,size,offset,length,keep,photo,null,-1,fetch);
    }
    public byte[] read(String scope,long size,long offset,int length,boolean keep,boolean photo,String group,long mediaTime,Fetch fetch)throws IOException {
        String policy=group==null?null:name(group,-1);
        if(size<0 || offset<0 || length<0 || offset>size-length)throw new IOException("Invalid ciphertext range");
        final long epoch;
        synchronized(this) {
            epoch=generation;
            long now=clock.getAsLong();
            if(lastPrune==Long.MIN_VALUE || now<lastPrune || now-lastPrune>=30000)prune();
        }
        if(length==0)return new byte[0];
        if(!keep || (!directory.isDirectory() && !directory.mkdirs())) {
            byte[] result=fetch.read(offset,length);
            try {checkGeneration(epoch);if(result.length!=length)throw new IOException("Short ciphertext read");return result;}
            catch(IOException failure){Arrays.fill(result,(byte)0);throw failure;}
        }
        byte[] result=new byte[length];int done=0;
        try {
            while(done<length) {
                long position=offset+done,block=position/BLOCK,base=block*BLOCK;
                int expected=(int)Math.min(BLOCK,size-base),within=(int)(position-base);
                int count=Math.min(length-done,expected-within);
                String name=name(scope,block)+(photo?"-photo":"");Gate gate=acquire(name);byte[] fetched=null;
                try {
                    // Only readers of the same immutable block wait. Network and disk reads
                    // never hold the cache-wide monitor, so unrelated playback can progress.
                    synchronized(gate) {
                        checkGeneration(epoch);
                        File file=new File(directory,name+".bin");long now=clock.getAsLong();boolean hit=false;
                        if(file.isFile() && file.length()==expected && !expired(file,now)) {
                            // Read the requested segment, not a fresh1MiB allocation for every
                            // authenticated-record/header read. readFully rejects truncation.
                            try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
                                if(input.length()!=expected)throw new EOFException("Cached length changed");
                                input.seek(within);input.readFully(result,done,count);
                                if(input.length()!=expected)throw new EOFException("Cached length changed");
                                file.setLastModified(now);hit=true;
                            }catch(IOException unavailable){discard(file);}
                        }else if(file.exists())discard(file);
                        checkGeneration(epoch);
                        if(!hit) {
                            fetched=fetch.read(base,expected);
                            if(fetched.length!=expected)throw new IOException("Short ciphertext read");
                            checkGeneration(epoch);
                            store(file,fetched,photo,epoch,policy,mediaTime);
                            System.arraycopy(fetched,within,result,done,count);
                        }
                        checkGeneration(epoch);done+=count;
                    }
                }finally {
                    if(fetched!=null)Arrays.fill(fetched,(byte)0);
                    release(name,gate);
                }
            }
            checkGeneration(epoch);return result;
        }catch(IOException|RuntimeException failure){Arrays.fill(result,(byte)0);throw failure;}
    }
    private synchronized boolean reserve(int count,long epoch)throws IOException {
        checkGeneration(epoch);
        if(cachedBytes+pendingBytes+count>cap)pruneTo(Math.max(0,cap-pendingBytes-count));
        if(cachedBytes+pendingBytes+count>cap)return false;
        makeRoom(FREE_RESERVE+pendingBytes+count);
        if(directory.getUsableSpace()<FREE_RESERVE+pendingBytes+count)return false;
        pendingBytes+=count;return true;
    }
    private synchronized File temporary()throws IOException {
        File file=File.createTempFile("range-",".tmp",directory);activeTemps.add(file);return file;
    }
    private synchronized void removeTemporary(File file) {
        if(file!=null){activeTemps.remove(file);file.delete();}
    }
    private void store(File file,byte[] bytes,boolean photo,long epoch,String policy,long mediaTime)throws IOException {
        if(!reserve(bytes.length,epoch))return;
        File temporary=null,receipt=null;
        try {
            temporary=temporary();
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            if(!photo || policy!=null) {
                receipt=temporary();
                try(DataOutputStream output=new DataOutputStream(new FileOutputStream(receipt))){
                    output.writeLong(clock.getAsLong());
                    if(policy!=null){output.writeUTF(policy);output.writeLong(mediaTime);}
                }
            }
            synchronized(this) {
                checkGeneration(epoch);
                // setCapacity may have changed while disk I/O was in progress.
                if(cachedBytes+bytes.length>cap)pruneTo(Math.max(0,cap-bytes.length));
                if(cachedBytes+bytes.length>cap)return;
                Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                try {
                    if(receipt!=null)Files.move(receipt.toPath(),age(file).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                    else age(file).delete();
                }catch(IOException badReceipt) {
                    // A video without a committed birth receipt must not linger indefinitely.
                    remove(file);throw badReceipt;
                }
                file.setLastModified(clock.getAsLong());cachedBytes+=bytes.length;
            }
        }catch(IOException unavailable){checkGeneration(epoch);}
        finally {
            removeTemporary(temporary);removeTemporary(receipt);
            synchronized(this){pendingBytes-=bytes.length;}
        }
    }
    private File age(File file) {
        String name=file.getName();return new File(directory,name.substring(0,name.length()-4)+".age");
    }
    private boolean expired(File file,long now) {
        long accessed=file.lastModified();
        if(accessed>now || accessed<=0)return true;
        File receipt=age(file);
        if(file.getName().endsWith("-photo.bin") && !receipt.exists())return now-accessed>=PHOTO_TTL;
        if(!receipt.isFile() || (receipt.length()!=Long.BYTES && receipt.length()!=82))return true;
        try(DataInputStream input=new DataInputStream(new FileInputStream(receipt))) {
            long born=input.readLong();
            if(born>now || born<=0)return true;
            if(receipt.length()==Long.BYTES)return now-born>=TTL;
            String policy=input.readUTF();long at=input.readLong();
            if(!policy.matches("[0-9a-f]{64}"))return true;
            long deadline=deadline(policy);
            if(deadline<=now || deadline>now+PHOTO_TTL)return true;
            Long current=playbackWindows.get(policy);
            return current!=null && at>=0 && Math.abs(current-at)>30*60*1000L;
        }catch(IOException unavailable){return true;}
    }
    private File policyFile(String hash){return new File(directory,"policy-"+hash+".life");}
    private long deadline(String hash) {
        Long known=policies.get(hash);if(known!=null)return known;
        File file=policyFile(hash);if(!file.isFile() || file.length()!=8)return 0;
        try(DataInputStream input=new DataInputStream(new FileInputStream(file))){long value=input.readLong();policies.put(hash,value);return value;}
        catch(IOException failure){return 0;}
    }
    /** Opaque expiry receipt only. Actual view counts and identities are in the sealed catalog. IO thread only. */
    public synchronized void retain(String group,long until) {
        long now=clock.getAsLong();if(until<=now || until>now+PHOTO_TTL)return;
        String hash=name(group,-1);if(deadline(hash)>=until)return;
        if(!directory.isDirectory() && !directory.mkdirs())return;
        File temporary=null;
        try {
            temporary=temporary();
            try(DataOutputStream output=new DataOutputStream(new FileOutputStream(temporary))){output.writeLong(until);}
            Files.move(temporary.toPath(),policyFile(hash).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            policies.put(hash,until);
        }catch(IOException unavailable){/* Caching is optional; authenticated streaming still works. */}
        finally{removeTemporary(temporary);}
    }
    /** Uses actual native playback timestamps, never byte/bitrate estimates. Active reads stay protected. */
    public void markPlaybackWindow(String group,long position) {
        if(position>=0)playbackWindows.put(name(group,-1),position);
    }
    /** Remove one retired object's disposable blocks without invalidating another viewer. */
    public synchronized void removeGroup(String group)throws IOException {
        String hash=name(group,-1);
        File[] files=directory.listFiles();
        if(files!=null)for(File receipt:files) {
            if(!AGE_NAME.matcher(receipt.getName()).matches() || receipt.length()!=82)continue;
            boolean belongs;
            try(DataInputStream input=new DataInputStream(new FileInputStream(receipt))) {
                input.readLong();belongs=hash.equals(input.readUTF());input.readLong();
            }
            if(!belongs)continue;
            String stem=receipt.getName().substring(0,receipt.getName().length()-4);
            if(gates.containsKey(stem))throw new IOException("Retired media still has an active reader");
            File block=new File(directory,stem+".bin");long bytes=block.length();
            if(block.exists() && !block.delete())throw new IOException("Cannot remove retired ciphertext");
            cachedBytes=Math.max(0,cachedBytes-bytes);
            if(receipt.exists() && !receipt.delete())throw new IOException("Cannot remove retired receipt");
        }
        File policy=policyFile(hash);
        if(policy.exists() && !policy.delete())throw new IOException("Cannot remove retired expiry");
        policies.remove(hash);playbackWindows.remove(hash);windowPruned.remove(hash);
    }
    public synchronized void playbackWindow(String group,long position) {
        if(position<0)return;
        String hash=name(group,-1);playbackWindows.put(hash,position);
        Long previous=windowPruned.put(hash,position/60000);
        if(previous==null || previous!=position/60000)prune();
    }
    private void remove(File file) {
        if(file.delete()) {File receipt=age(file);if(receipt.isFile())receipt.delete();}
    }
    private synchronized void discard(File file) {
        remove(file);
        // Android may have removed/truncated cache files behind our back. Reconcile rather
        // than subtracting their new length from an old accounting snapshot.
        pruneTo(cap);
    }
    /** Reclaims only this disposable cache before encrypted upload staging needs disk space. */
    public synchronized void makeRoom(long requiredFree) {
        if(requiredFree<0)throw new IllegalArgumentException("Invalid reservation");
        if(directory.getUsableSpace()>=requiredFree)return;
        prune();
        long missing=Math.max(0,requiredFree-directory.getUsableSpace());
        if(missing>0)pruneTo(Math.max(0,cachedBytes-missing));
    }
    public synchronized void prune() { pruneTo(cap); }
    private void pruneTo(long limit) {
        File[] files=directory.listFiles();if(files==null){cachedBytes=0;return;}
        long now=clock.getAsLong(),bytes=0;lastPrune=now;
        List<File> kept=new ArrayList<>();
        for(File file:files) {
            String name=file.getName();
            if(POLICY_NAME.matcher(name).matches()) {
                String hash=name.substring(7,name.length()-5);long until=deadline(hash);
                if(file.isFile() && (until<=now || until>now+PHOTO_TTL)){file.delete();policies.remove(hash);playbackWindows.remove(hash);windowPruned.remove(hash);}
                continue;
            }
            if(AGE_NAME.matcher(name).matches()) {
                if(file.isFile() && !new File(directory,name.substring(0,name.length()-4)+".bin").isFile())file.delete();
                continue;
            }
            if(name.startsWith("range-") && name.endsWith(".tmp")) {
                long modified=file.lastModified();
                if(file.isFile() && !activeTemps.contains(file) && (modified>now || modified<=0 || now-modified>=600000))file.delete();
                continue;
            }
            // Never remove unexpected files or directories, even in our private cache root.
            if(!BLOCK_NAME.matcher(name).matches() || !file.isFile())continue;
            boolean active=gates.containsKey(name.substring(0,name.length()-4));
            if(!active && expired(file,now)){remove(file);if(file.isFile())bytes+=file.length();continue;}
            kept.add(file);bytes+=file.length();
        }
        kept.sort(Comparator.comparingLong(File::lastModified));
        for(File file:kept)if(bytes>limit && !gates.containsKey(file.getName().substring(0,file.getName().length()-4))) {
            long length=file.length();remove(file);if(!file.exists())bytes-=length;
        }
        cachedBytes=bytes;
    }
    public synchronized void clear() {
        generation++;lastPrune=Long.MIN_VALUE;cachedBytes=0;policies.clear();playbackWindows.clear();windowPruned.clear();
        File[] files=directory.listFiles();
        if(files!=null)for(File file:files) {
            String name=file.getName();
            if(file.isFile() && (BLOCK_NAME.matcher(name).matches() || AGE_NAME.matcher(name).matches() || POLICY_NAME.matcher(name).matches() || name.startsWith("range-") && name.endsWith(".tmp")))file.delete();
            if(BLOCK_NAME.matcher(name).matches() && file.isFile())cachedBytes+=file.length();
        }
    }
}
