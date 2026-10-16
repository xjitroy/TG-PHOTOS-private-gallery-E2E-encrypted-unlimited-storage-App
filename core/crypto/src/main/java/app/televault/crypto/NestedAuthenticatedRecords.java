package app.televault.crypto;

import java.io.IOException;
import java.util.UUID;

/** Two independently keyed, context-bound TVLT1 layers; plaintext is retained only in bounded reader RAM. */
public final class NestedAuthenticatedRecords implements AutoCloseable {
    private final AuthenticatedRecords outer, inner;
    public NestedAuthenticatedRecords(AuthenticatedRecords.CipherInput input, byte[] innerKey, byte[] outerKey,
                                      UUID photosVault, UUID privateVault, UUID object, long size, long cipherSize) throws IOException {
        outer = new AuthenticatedRecords(input, outerKey, privateVault, object, VaultCrypto.encryptedLength(size), cipherSize);
        try {
            inner = new AuthenticatedRecords((position, bytes, offset, length) -> {
                if (outer.read(position, bytes, offset, length) != length) throw new IOException("Truncated inner layer");
            }, innerKey, photosVault, object, size, VaultCrypto.encryptedLength(size));
        } catch (Throwable failure) {
            outer.close();
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Nested authentication failed", failure);
        }
    }
    public AuthenticatedRecords reader() { return inner; }
    @Override public void close() { inner.close(); outer.close(); }
}
