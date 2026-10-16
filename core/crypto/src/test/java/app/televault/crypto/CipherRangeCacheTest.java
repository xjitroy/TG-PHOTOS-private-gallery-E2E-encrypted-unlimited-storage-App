package app.televault.crypto;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
public class CipherRangeCacheTest {
 @Test public void crossBlockReadsReuseCipherExpireWithoutSlidingAndSaverBypasses()throws Exception {
  var directory=Files.createTempDirectory("cipher-cache");AtomicLong now=new AtomicLong(1700000000000L);AtomicInteger reads=new AtomicInteger();
  byte[] cipher=new byte[2100000];new java.security.SecureRandom().nextBytes(cipher);
  var cache=new CipherRangeCache(directory.toFile(),4*1024*1024,now::get);
  CipherRangeCache.Fetch fetch=(offset,length)->{reads.incrementAndGet();return Arrays.copyOfRange(cipher,(int)offset,(int)offset+length);};
  assertArrayEquals(Arrays.copyOfRange(cipher,1048000,1098000),cache.read("vault/file/part",cipher.length,1048000,50000,true,fetch));assertEquals(2,reads.get());
  cache.read("vault/file/part",cipher.length,1048000,50000,true,fetch);assertEquals(2,reads.get());
  now.addAndGet(CipherRangeCache.TTL);cache.read("vault/file/part",cipher.length,1048000,50000,true,fetch);assertEquals(4,reads.get());
  cache.read("vault/file/part",cipher.length,1048000,50000,false,fetch);assertEquals(5,reads.get());
  cache.read("other-vault/file/part",cipher.length,1048000,50000,true,fetch);assertEquals(7,reads.get());
  assertThrows(java.io.IOException.class,()->cache.read("x",2,1,2,true,fetch));cache.clear();assertEquals(0,directory.toFile().list().length);Files.delete(directory);
 }
 @Test public void capAndTruncatedFileCannotReturnInventedBytes()throws Exception {
  var dir=Files.createTempDirectory("cipher-limit");var c=new CipherRangeCache(dir.toFile(),1048576,System::currentTimeMillis);
  byte[] bytes=new byte[1500000];Arrays.fill(bytes,(byte)73);c.read("a",bytes.length,0,1500000,true,(o,n)->Arrays.copyOfRange(bytes,(int)o,(int)o+n));
  assertTrue(Arrays.stream(dir.toFile().listFiles()).filter(f->f.getName().endsWith(".bin")).mapToLong(java.io.File::length).sum()<=1048576);
  assertThrows(java.io.IOException.class,()->c.read("b",bytes.length,0,1,true,(o,n)->new byte[0]));c.clear();Files.delete(dir);
 }
 @Test public void cacheTamperingCannotExposeUnauthenticatedPlaintext()throws Exception {
  var dir=Files.createTempDirectory("cipher-auth");var cache=new CipherRangeCache(dir.toFile(),1024*1024,System::currentTimeMillis);
  byte[] master=VaultCrypto.randomKey(),plain="private audio video and photos".getBytes();var vault=java.util.UUID.randomUUID();var object=java.util.UUID.randomUUID();var out=new java.io.ByteArrayOutputStream();VaultCrypto.encrypt(new java.io.ByteArrayInputStream(plain),out,master,vault,object,plain.length,(n)->{});byte[] sealed=out.toByteArray();
  AuthenticatedRecords.CipherInput input=(position,bytes,offset,length)->{byte[] fetched=cache.read("owner/vault/file",sealed.length,position,length,true,(at,n)->Arrays.copyOfRange(sealed,(int)at,(int)at+n));System.arraycopy(fetched,0,bytes,offset,length);};
  try(var reader=new AuthenticatedRecords(input,master,vault,object,plain.length,sealed.length)){byte[] result=new byte[plain.length];reader.read(0,result,0,result.length);assertArrayEquals(plain,result);}
  var stored=Arrays.stream(dir.toFile().listFiles()).filter(f->f.getName().endsWith(".bin")).findFirst().get();byte[] broken=Files.readAllBytes(stored.toPath());broken[broken.length-1]^=1;Files.write(stored.toPath(),broken);
  assertThrows(java.io.IOException.class,()->new AuthenticatedRecords(input,master,vault,object,plain.length,sealed.length));cache.clear();Files.delete(dir);
 }
 @Test public void unrelatedFetchAndCachedReadProgressWhileNetworkIsStalled()throws Exception {
  var dir=Files.createTempDirectory("cipher-parallel");var cache=new CipherRangeCache(dir.toFile(),4*1024*1024,System::currentTimeMillis);
  cache.read("cached",4,0,4,true,(o,n)->new byte[]{1,2,3,4});
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
  try {
   var stalled=pool.submit(()->cache.read("slow",4,0,4,true,(o,n)->{entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}return new byte[]{5,6,7,8};}));
   assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
   assertArrayEquals(new byte[]{9,10,11,12},pool.submit(()->cache.read("next",4,0,4,true,(o,n)->new byte[]{9,10,11,12})).get(2,java.util.concurrent.TimeUnit.SECONDS));
   assertArrayEquals(new byte[]{1,2,3,4},pool.submit(()->cache.read("cached",4,0,4,true,(o,n)->{throw new AssertionError("cache miss");})).get(2,java.util.concurrent.TimeUnit.SECONDS));
   release.countDown();assertArrayEquals(new byte[]{5,6,7,8},stalled.get(2,java.util.concurrent.TimeUnit.SECONDS));
  }finally{release.countDown();pool.shutdownNow();cache.clear();Files.delete(dir);}
 }
 @Test public void clearingCacheRejectsInFlightFetchAndCannotRepopulateIt()throws Exception {
  var dir=Files.createTempDirectory("cipher-clear-race");var cache=new CipherRangeCache(dir.toFile(),2*1024*1024,System::currentTimeMillis);
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
  try {
   var pending=pool.submit(()->cache.read("secret",4,0,4,true,(o,n)->{entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}return new byte[4];}));
   assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));cache.clear();release.countDown();
   var error=assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue(error.getCause() instanceof java.io.IOException);
   assertEquals(0,dir.toFile().list().length);
  }finally{release.countDown();pool.shutdownNow();cache.clear();Files.delete(dir);}
 }
 @Test public void sameBlockConcurrentReadersFetchOnce()throws Exception {
  var dir=Files.createTempDirectory("cipher-coalesce");var cache=new CipherRangeCache(dir.toFile(),2*1024*1024,System::currentTimeMillis);var calls=new AtomicInteger();
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}return new byte[]{1,2,3,4};};
  try {
   var a=pool.submit(()->cache.read("same",4,0,4,true,fetch));assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));var b=pool.submit(()->cache.read("same",4,0,4,true,fetch));release.countDown();
   assertArrayEquals(a.get(2,java.util.concurrent.TimeUnit.SECONDS),b.get(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,calls.get());
  }finally{release.countDown();pool.shutdownNow();cache.clear();Files.delete(dir);}
 }
 @Test public void unavailableDirectoryDoesNotCrashStartupOrWriteUnencryptedFiles()throws Exception {
  var notDirectory=Files.createTempFile("cipher-unavailable", ".bin");
  var cache=new CipherRangeCache(notDirectory.toFile(),1048576,System::currentTimeMillis);
  assertArrayEquals(new byte[]{7,8,9},cache.read("owner/file",3,0,3,true,(offset,length)->new byte[]{7,8,9}));
  assertEquals(0,Files.size(notDirectory));
  cache.clear();Files.delete(notDirectory);
 }


 @Test public void videoExpiresAtCreationDeadlineDespiteRepeatedReads()throws Exception {
  var dir=Files.createTempDirectory("video-deadline");var now=new AtomicLong(1700000000000L);var calls=new AtomicInteger();
  var cache=new CipherRangeCache(dir.toFile(),1048576,now::get);
  CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();return new byte[n];};
  cache.read("video",4,0,4,true,false,fetch);now.addAndGet(CipherRangeCache.TTL-1);
  cache.read("video",4,0,4,true,false,fetch);assertEquals(1,calls.get());now.incrementAndGet();
  cache.read("video",4,0,4,true,false,fetch);assertEquals(2,calls.get());cache.clear();Files.delete(dir);
 }
 @Test public void photosSlideIdleDeadlineAndRestartRetainsPolicy()throws Exception {
  var dir=Files.createTempDirectory("photo-idle");var now=new AtomicLong(1700000000000L);var calls=new AtomicInteger();
  CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();return new byte[n];};
  var cache=new CipherRangeCache(dir.toFile(),1048576,now::get);cache.read("photo",4,0,4,true,true,fetch);
  now.addAndGet(6*CipherRangeCache.TTL);cache.read("photo",4,0,4,true,true,fetch);assertEquals(1,calls.get());
  cache=new CipherRangeCache(dir.toFile(),1048576,now::get);now.addAndGet(6*CipherRangeCache.TTL);
  cache.read("photo",4,0,4,true,true,fetch);assertEquals(1,calls.get());now.addAndGet(CipherRangeCache.PHOTO_TTL);
  cache.prune();assertEquals(0,cache.bytes());cache.clear();Files.delete(dir);
 }
 @Test public void shrinkingQuotaEvictsLeastRecentlyReadAndKeepsRecent()throws Exception {
  var dir=Files.createTempDirectory("cache-lru");var now=new AtomicLong(1700000000000L);
  var cache=new CipherRangeCache(dir.toFile(),3*1048576,now::get);CipherRangeCache.Fetch fetch=(o,n)->new byte[n];
  cache.read("a",1048576,0,1,true,true,fetch);now.incrementAndGet();cache.read("b",1048576,0,1,true,true,fetch);
  now.incrementAndGet();cache.read("a",1048576,0,1,true,true,fetch);cache.setCapacity(1048576);
  assertEquals(1048576,cache.bytes());cache.read("a",1048576,0,1,true,true,(o,n)->{throw new AssertionError("recent data lost");});
  var misses=new AtomicInteger();now.incrementAndGet();cache.read("b",1048576,0,1,true,true,(o,n)->{misses.incrementAndGet();return new byte[n];});
  assertEquals(1,misses.get());assertEquals(1048576,cache.bytes());cache.clear();Files.delete(dir);
 }
 @Test public void corruptedAgeCannotExtendVideoRetentionOrBreakPlayback()throws Exception {
  var dir=Files.createTempDirectory("cache-age");var now=new AtomicLong(1700000000000L);var calls=new AtomicInteger();
  var cache=new CipherRangeCache(dir.toFile(),1048576,now::get);CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();return new byte[n];};
  cache.read("v",4,0,4,true,fetch);var age=Arrays.stream(dir.toFile().listFiles()).filter(f->f.getName().endsWith(".age")).findFirst().get();
  Files.write(age.toPath(),new byte[0]);cache.read("v",4,0,4,true,fetch);assertEquals(2,calls.get());
  Files.delete(age.toPath());cache.read("v",4,0,4,true,fetch);assertEquals(3,calls.get());cache.clear();Files.delete(dir);
 }

 @Test public void stagingReservationOnlyReclaimsOwnedTemporaryCiphertext()throws Exception {
  var dir=Files.createTempDirectory("cache-reservation");var original=Files.createTempFile("device-original", ".jpg");Files.write(original,new byte[]{1,2,3});
  var cache=new CipherRangeCache(dir.toFile(),1048576,System::currentTimeMillis);
  cache.read("temporary",1048576,0,1,true,(o,n)->new byte[n]);assertEquals(1048576,cache.bytes());
  cache.makeRoom(dir.toFile().getUsableSpace()+1048576);assertEquals(0,cache.bytes());
  assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(original));cache.clear();Files.delete(dir);Files.delete(original);
 }
 @Test public void truncatedCachedBlockRefetchesAndReconcilesAccounting()throws Exception {
  var dir=Files.createTempDirectory("cache-truncated");var cache=new CipherRangeCache(dir.toFile(),1048576,System::currentTimeMillis);var calls=new AtomicInteger();
  byte[] original=new byte[1048576];new java.security.SecureRandom().nextBytes(original);
  CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();return Arrays.copyOfRange(original,(int)o,(int)o+n);};
  try {
   cache.read("a",original.length,17,90,true,true,fetch);
   var stored=Arrays.stream(dir.toFile().listFiles()).filter(f->f.getName().endsWith(".bin")).findFirst().get();
   Files.write(stored.toPath(),new byte[]{1,2,3});
   assertArrayEquals(Arrays.copyOfRange(original,17,107),cache.read("a",original.length,17,90,true,true,fetch));
   assertEquals(2,calls.get());assertEquals(original.length,cache.bytes());
  }finally{cache.clear();Files.delete(dir);}
 }
 @Test public void quotaPruningPreservesAnActiveCachedReader()throws Exception {
  var dir=Files.createTempDirectory("cache-active-lru");var now=new AtomicLong(1700000000000L);
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  var workerCalls=new AtomicInteger();var worker=new AtomicReference<Thread>();
  java.util.function.LongSupplier clock=()->{
   if(Thread.currentThread()==worker.get() && workerCalls.incrementAndGet()==2) {
    entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException failure){throw new AssertionError(failure);}
   }
   return now.get();
  };
  var cache=new CipherRangeCache(dir.toFile(),2*1048576,clock);var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
  try {
   cache.read("active",1048576,0,1,true,true,(o,n)->new byte[n]);now.incrementAndGet();
   cache.read("inactive",1048576,0,1,true,true,(o,n)->new byte[n]);
   var pending=pool.submit(()->{worker.set(Thread.currentThread());return cache.read("active",1048576,700000,5,true,true,(o,n)->{throw new AssertionError("active cached block evicted");});});
   assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));cache.setCapacity(1048576);release.countDown();
   assertArrayEquals(new byte[5],pending.get(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1048576,cache.bytes());
   cache.read("active",1048576,2,1,true,true,(o,n)->{throw new AssertionError("active block lost");});
  }finally{release.countDown();pool.shutdownNow();cache.clear();Files.delete(dir);}
 }
 @Test public void futureTemporaryFilesExpireAndUnknownFilesArePreserved()throws Exception {
  var dir=Files.createTempDirectory("cache-owned-cleanup");var now=new AtomicLong(1700000000000L);
  var future=dir.resolve("range-abandoned.tmp");Files.write(future,new byte[4]);future.toFile().setLastModified(now.get()+1000);
  var unknown=dir.resolve("device-original.jpg");Files.write(unknown,new byte[]{7,8,9});
  var unknownBin=dir.resolve("catalog.bin");Files.write(unknownBin,new byte[]{4,5});
  var cache=new CipherRangeCache(dir.toFile(),1048576,now::get);
  try {
   assertArrayEquals(new byte[0],cache.read("empty",0,0,0,true,(o,n)->{throw new AssertionError("zero-byte read fetched");}));
   assertFalse(Files.exists(future));cache.clear();
   assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(unknown));assertArrayEquals(new byte[]{4,5},Files.readAllBytes(unknownBin));
  }finally{cache.clear();Files.deleteIfExists(future);Files.delete(unknown);Files.delete(unknownBin);Files.delete(dir);}
 }
 @Test public void malformedLongAgeReceiptFailsClosedAndDoesNotBreakPlayback()throws Exception {
  var dir=Files.createTempDirectory("cache-age-trailing");var now=new AtomicLong(1700000000000L);var calls=new AtomicInteger();
  var cache=new CipherRangeCache(dir.toFile(),1048576,now::get);CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();return new byte[n];};
  try {
   cache.read("v",4,0,4,true,false,fetch);
   var receipt=Arrays.stream(dir.toFile().listFiles()).filter(f->f.getName().endsWith(".age")).findFirst().get();
   Files.write(receipt.toPath(),new byte[]{8},StandardOpenOption.APPEND);
   assertArrayEquals(new byte[4],cache.read("v",4,0,4,true,false,fetch));assertEquals(2,calls.get());assertEquals(4,cache.bytes());
  }finally{cache.clear();Files.delete(dir);}
 }


 @Test public void adaptiveFileDeadlineSurvivesRestartAndExtendsAllPartsOnlyOnExplicitView()throws Exception {
  var dir=Files.createTempDirectory("adaptive-cipher");var now=new AtomicLong(1700000000000L);var calls=new AtomicInteger();
  CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();return new byte[n];};
  var cache=new CipherRangeCache(dir.toFile(),2*1048576,now::get);
  cache.retain("owner/vault/photo",now.get()+CipherRangeCache.TTL);
  cache.read("p/part1",4,0,4,true,true,"owner/vault/photo",-1,fetch);
  cache.read("p/part2",4,0,4,true,true,"owner/vault/photo",-1,fetch);
  now.addAndGet(1000);cache.retain("owner/vault/photo",now.get()+2*CipherRangeCache.TTL);
  cache=new CipherRangeCache(dir.toFile(),2*1048576,now::get);now.addAndGet(CipherRangeCache.TTL);
  cache.read("p/part1",4,0,4,true,true,"owner/vault/photo",-1,fetch);cache.read("p/part2",4,0,4,true,true,"owner/vault/photo",-1,fetch);assertEquals(2,calls.get());
  now.addAndGet(CipherRangeCache.TTL);cache.prune();assertEquals(0,cache.bytes());cache.clear();Files.delete(dir);
 }
 @Test public void nativeTimeWindowReclaimsDistantRegionsWithoutGuessingByteBitrate()throws Exception {
  var dir=Files.createTempDirectory("video-window");var now=new AtomicLong(1700000000000L);var calls=new AtomicInteger();
  var cache=new CipherRangeCache(dir.toFile(),4*1048576,now::get);CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();return new byte[n];};
  cache.retain("movie",now.get()+CipherRangeCache.TTL);cache.playbackWindow("movie",0);
  cache.read("variable-bitrate/first",4,0,4,true,false,"movie",0,fetch);
  cache.playbackWindow("movie",31*60000L+1);cache.prune();assertEquals(0,cache.bytes());
  cache.read("variable-bitrate/current",4,0,4,true,false,"movie",31*60000L+1,fetch);
  cache.playbackWindow("movie",32*60000L);cache.read("variable-bitrate/current",4,0,4,true,false,"movie",32*60000L,fetch);assertEquals(2,calls.get());
  cache.playbackWindow("movie",0);cache.prune();assertEquals(0,cache.bytes());cache.clear();Files.delete(dir);
 }
 @Test public void corruptedAdaptiveReceiptFailsClosedAndDoesNotTouchOriginals()throws Exception {
  var dir=Files.createTempDirectory("adaptive-corrupt");var now=new AtomicLong(1700000000000L);var calls=new AtomicInteger();
  var cache=new CipherRangeCache(dir.toFile(),1048576,now::get);CipherRangeCache.Fetch fetch=(o,n)->{calls.incrementAndGet();return new byte[n];};
  cache.retain("file",now.get()+CipherRangeCache.TTL);cache.read("part",4,0,4,true,false,"file",0,fetch);
  var receipt=Arrays.stream(dir.toFile().listFiles()).filter(f->f.getName().endsWith(".life")).findFirst().get();Files.write(receipt.toPath(),new byte[0]);
  cache=new CipherRangeCache(dir.toFile(),1048576,now::get);cache.read("part",4,0,4,true,false,"file",0,fetch);assertEquals(2,calls.get());
  cache.clear();assertEquals(0,dir.toFile().list().length);Files.delete(dir);
 }
 @Test public void retiringOneObjectKeepsAnotherVaultsCachedPhoto()throws Exception {
  var directory=Files.createTempDirectory("cipher-retired-scope");
  long now=System.currentTimeMillis();var cache=new CipherRangeCache(directory.toFile(),4*1024*1024,()->now);
  byte[] a={1,2,3,4},b={5,6,7,8};var readsA=new AtomicInteger();var readsB=new AtomicInteger();
  try {
   cache.retain("owner/old/object",now+CipherRangeCache.TTL);cache.retain("owner/current/object",now+CipherRangeCache.TTL);
   cache.read("owner/old/object/part",4,0,4,true,true,"owner/old/object",-1,(o,n)->{readsA.incrementAndGet();return a.clone();});
   cache.read("owner/current/object/part",4,0,4,true,true,"owner/current/object",-1,(o,n)->{readsB.incrementAndGet();return b.clone();});
   cache.removeGroup("owner/old/object");
   assertArrayEquals(b,cache.read("owner/current/object/part",4,0,4,true,true,"owner/current/object",-1,(o,n)->{readsB.incrementAndGet();return b.clone();}));
   assertEquals(1,readsB.get());
   assertArrayEquals(a,cache.read("owner/old/object/part",4,0,4,true,true,"owner/old/object",-1,(o,n)->{readsA.incrementAndGet();return a.clone();}));
   assertEquals(2,readsA.get());
  }finally{cache.clear();Files.delete(directory);}
 }
}
