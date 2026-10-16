package app.televault.crypto;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;

public class AuthenticatedRecordsTest {
    @Test public void partialReadFailureWipesReturnedPrefixAndPermanentlyClosesReader() throws Exception {
        byte[] original = new byte[VaultCrypto.RECORD_BYTES + 20]; Arrays.fill(original, (byte)42);
        var key = VaultCrypto.randomKey(); var vault = UUID.randomUUID(); var object = UUID.randomUUID();
        var out = new ByteArrayOutputStream();
        VaultCrypto.encrypt(new ByteArrayInputStream(original), out, key, vault, object, original.length, n -> {});
        byte[] encrypted = out.toByteArray();
        encrypted[VaultCrypto.HEADER_BYTES + VaultCrypto.RECORD_BYTES + 20 + 5] ^= 1;
        try (var reader = new AuthenticatedRecords((p, b, o, n) -> System.arraycopy(encrypted, (int)p, b, o, n), key, vault, object, original.length, encrypted.length)) {
            byte[] buffer = new byte[24]; Arrays.fill(buffer, (byte)17);
            assertThrows(IOException.class, () -> reader.read(VaultCrypto.RECORD_BYTES - 10, buffer, 2, 20));
            for (int i = 2; i < 12; i++) assertEquals(0, buffer[i]);
            assertEquals(17, buffer[0]); assertEquals(17, buffer[12]);
            assertThrows(IOException.class, () -> reader.read(0, buffer, 0, 1));
        }
    }
    @Test public void seekAcrossRecordsAuthenticatesBeforeReturningBytes() throws Exception {
        byte[] original = new byte[VaultCrypto.RECORD_BYTES*3+79];new Random(42).nextBytes(original);
        var key=VaultCrypto.randomKey();var vault=UUID.randomUUID();var object=UUID.randomUUID();var out=new ByteArrayOutputStream();
        VaultCrypto.encrypt(new ByteArrayInputStream(original),out,key,vault,object,original.length,n->{});byte[] encrypted=out.toByteArray();
        AuthenticatedRecords.CipherInput input=(position, target,offset,length)->System.arraycopy(encrypted,Math.toIntExact(position),target,offset,length);
        try(var records=new AuthenticatedRecords(input,key,vault,object,original.length,encrypted.length)) {
            for(int position:new int[]{original.length-50, VaultCrypto.RECORD_BYTES-25, 0, VaultCrypto.RECORD_BYTES*2+5}) {
                byte[] slice=new byte[50];assertEquals(50,records.read(position,slice,0,50));assertArrayEquals(Arrays.copyOfRange(original,position,position+50),slice);
            }
            assertEquals(-1,records.read(original.length,new byte[1],0,1));
        }
        encrypted[VaultCrypto.HEADER_BYTES+VaultCrypto.RECORD_BYTES+20+9]^=1;
        try(var records=new AuthenticatedRecords(input,key,vault,object,original.length,encrypted.length)) {
            byte[] untouched=new byte[50];Arrays.fill(untouched,(byte)17);
            assertThrows(IOException.class,()->records.read(VaultCrypto.RECORD_BYTES,untouched,0,50));
            byte[] expected=new byte[50];Arrays.fill(expected,(byte)17);assertArrayEquals(expected,untouched);
        }
        encrypted[56]^=1;
        assertThrows(IOException.class,()->new AuthenticatedRecords(input,key,vault,object,original.length,encrypted.length));
    }
    @Test public void wrongContextEmptyFileAndBounds() throws Exception {
        var key=VaultCrypto.randomKey();var vault=UUID.randomUUID();var object=UUID.randomUUID();var out=new ByteArrayOutputStream();
        VaultCrypto.encrypt(new ByteArrayInputStream(new byte[0]),out,key,vault,object,0,n->{});byte[] encrypted=out.toByteArray();
        AuthenticatedRecords.CipherInput input=(p,b,o,n)->System.arraycopy(encrypted,(int)p,b,o,n);
        try(var records=new AuthenticatedRecords(input,key,vault,object,0,encrypted.length)){assertEquals(-1,records.read(0,new byte[1],0,1));assertThrows(IOException.class,()->records.read(-1,new byte[1],0,1));}
        assertThrows(IOException.class,()->new AuthenticatedRecords(input,key,vault,UUID.randomUUID(),0,encrypted.length));
        encrypted[encrypted.length-1]^=1;assertThrows(IOException.class,()->new AuthenticatedRecords(input,key,vault,object,0,encrypted.length));
    }
}
