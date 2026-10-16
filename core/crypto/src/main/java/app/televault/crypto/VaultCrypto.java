package app.televault.crypto;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** TVLT1 authenticated records. No plaintext is emitted before a complete GCM tag verifies. */
public final class VaultCrypto {
    public static final int RECORD_BYTES = 1024 * 1024;
    public static final int HEADER_BYTES = 57;
    private static final byte[] MAGIC = "TVLT1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private VaultCrypto() {}

    public interface Progress { void update(long processed); }
    public static byte[] randomKey() { return randomBytes(32); }
    public static byte[] randomBytes(int size) { byte[] bytes=new byte[size]; RANDOM.nextBytes(bytes); return bytes; }

    public static byte[] seal(byte[] key, byte[] plain, byte[] context) throws GeneralSecurityException {
        if (key.length != 32) throw new IllegalArgumentException("Invalid key");
        byte[] iv=randomBytes(12);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(context);
        byte[] encrypted=cipher.doFinal(plain);
        return ByteBuffer.allocate(12+encrypted.length).put(iv).put(encrypted).array();
    }

    public static byte[] open(byte[] key, byte[] envelope, byte[] context) throws GeneralSecurityException {
        if (key.length!=32 || envelope.length<28 || envelope.length>16*1024*1024) throw new GeneralSecurityException("Invalid envelope");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(envelope,12)));
        cipher.updateAAD(context);
        return cipher.doFinal(envelope,12,envelope.length-12);
    }

    /** Fixed bounded Argon2id policy; untrusted descriptors cannot request arbitrary RAM/time. */
    public static byte[] recoveryKey(char[] phrase, byte[] salt) {
        if (salt.length!=16 || !RecoveryPhrasePolicy.accepts(phrase)) throw new IllegalArgumentException("Invalid recovery input");
        var parameters=new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt).withMemoryAsKB(65536).withIterations(3).withParallelism(1).build();
        var generator=new Argon2BytesGenerator(); generator.init(parameters);
        byte[] key=new byte[32]; generator.generateBytes(phrase,key); return key;
    }

    public static long encryptedLength(long length) {
        if (length<0 || length>64L*1024*1024*1024) throw new IllegalArgumentException("Unsupported file size");
        long records=Math.max(1,(length+RECORD_BYTES-1)/RECORD_BYTES);
        return HEADER_BYTES+length+records*20;
    }

    public static byte[] encrypt(InputStream input, OutputStream output, byte[] key, UUID vault, UUID object,
                                 long length, Progress progress) throws IOException, GeneralSecurityException {
        encryptedLength(length);
        byte[] prefix=randomBytes(8);
        byte[] header=ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putLong(vault.getMostSignificantBits()).putLong(vault.getLeastSignificantBits())
            .putLong(object.getMostSignificantBits()).putLong(object.getLeastSignificantBits()).putInt(RECORD_BYTES).putLong(length).put(prefix).array();
        var data=new DataOutputStream(output); data.write(header);
        var digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[RECORD_BYTES]; long done=0; int index=0;
        try {
            do {
                int size=(int)Math.min(RECORD_BYTES,length-done);
                readFully(input,buffer,size); digest.update(buffer,0,size);
                Cipher cipher=recordCipher(Cipher.ENCRYPT_MODE,key,prefix,index,header,size);
                byte[] sealed=cipher.doFinal(buffer,0,size);
                data.writeInt(size); data.write(sealed); done+=size; index++;
                progress.update(done);
            } while (done<length);
            if (input.read()!=-1) throw new IOException("Source changed while reading");
            data.flush(); return digest.digest();
        } finally { Arrays.fill(buffer,(byte)0); }
    }

    public static void decrypt(InputStream input, OutputStream output, byte[] key, UUID vault, UUID object,
                               long expectedLength, byte[] expectedHash, Progress progress) throws IOException, GeneralSecurityException {
        encryptedLength(expectedLength);
        var data=new DataInputStream(input); byte[] header=new byte[HEADER_BYTES]; data.readFully(header);
        var fields=ByteBuffer.wrap(header); byte[] magic=new byte[5]; fields.get(magic);
        UUID actualVault=new UUID(fields.getLong(),fields.getLong()); UUID actualObject=new UUID(fields.getLong(),fields.getLong());
        int recordSize=fields.getInt(); long length=fields.getLong(); byte[] prefix=new byte[8]; fields.get(prefix);
        if (!Arrays.equals(magic,MAGIC) || !actualVault.equals(vault) || !actualObject.equals(object) || recordSize!=RECORD_BYTES || length!=expectedLength)
            throw new GeneralSecurityException("Invalid authenticated context");
        var digest=MessageDigest.getInstance("SHA-256"); long done=0; int index=0;
        do {
            int size=data.readInt(); int expected=(int)Math.min(RECORD_BYTES,length-done);
            if (size!=expected) throw new GeneralSecurityException("Invalid record boundary");
            byte[] sealed=new byte[size+16]; data.readFully(sealed);
            byte[] plain=recordCipher(Cipher.DECRYPT_MODE,key,prefix,index,header,size).doFinal(sealed);
            try { digest.update(plain); output.write(plain); } finally { Arrays.fill(plain,(byte)0); }
            done+=size; index++; progress.update(done);
        } while(done<length);
        if (data.read()!=-1 || !MessageDigest.isEqual(digest.digest(),expectedHash)) throw new GeneralSecurityException("Integrity check failed");
        output.flush();
    }

    private static Cipher recordCipher(int mode, byte[] key, byte[] prefix, int index, byte[] header, int size) throws GeneralSecurityException {
        if(key.length!=32) throw new GeneralSecurityException("Invalid content key");
        byte[] iv=ByteBuffer.allocate(12).put(prefix).putInt(index).array();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(header); cipher.updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(size).array()); return cipher;
    }

    private static void readFully(InputStream input, byte[] buffer, int size) throws IOException {
        int offset=0;
        while(offset<size) { int count=input.read(buffer,offset,size-offset); if(count<0) throw new EOFException("Source shortened"); if(count==0) continue; offset+=count; }
    }
}
