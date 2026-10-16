package app.televault.data;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.*;

/** Standard WinZip AES-256 AE-2. Public filenames are generic; the real-name index is encrypted. */
public final class VaultZipSupport {
    private VaultZipSupport() {}
    public static String password() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    public static ZipParameters entry(String name, long length) {
        if ((!name.matches("File-[0-9]{6}(\\.[a-z0-9]{1,10})?") && !name.equals("index.json")) || length < 0) throw new IllegalArgumentException("Invalid ZIP entry");
        ZipParameters p = new ZipParameters();
        p.setFileNameInZip(name); p.setEntrySize(length); p.setEncryptFiles(true);
        p.setEncryptionMethod(EncryptionMethod.AES); p.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        p.setAesVersion(AesVersion.TWO); p.setCompressionMethod(CompressionMethod.STORE);
        return p;
    }
    public static String name(int index, String mime) {
        if (index < 0 || index >= 1000) throw new IllegalArgumentException("Invalid ZIP index");
        String extension;
        switch (mime) {
            case "image/jpeg": extension = "jpg"; break;
            case "image/png": extension = "png"; break;
            case "image/webp": extension = "webp"; break;
            case "image/gif": extension = "gif"; break;
            case "image/heic": extension = "heic"; break;
            case "video/mp4": extension = "mp4"; break;
            case "video/x-matroska": extension = "mkv"; break;
            case "audio/mpeg": extension = "mp3"; break;
            case "audio/mp4": extension = "m4a"; break;
            case "audio/wav": extension = "wav"; break;
            case "audio/flac": extension = "flac"; break;
            case "application/pdf": extension = "pdf"; break;
            case "text/plain": extension = "txt"; break;
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": extension = "xlsx"; break;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": extension = "docx"; break;
            default: extension = "bin";
        }
        return String.format(java.util.Locale.ROOT, "File-%06d.%s", index + 1, extension);
    }
}
