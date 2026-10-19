package app.televault.domain;

import java.util.*;

/** Authenticated cloud metadata only. Names narrow ties; a whole-file digest decides. */
public final class BackupCopyIndex {
    public record Copy(String id, String name, long bytes, String hash, boolean live) {}
    private final Map<Long, List<Copy>> sizes = new HashMap<>();
    public BackupCopyIndex(List<Copy> copies) {
        for (Copy copy : copies) {
            if (BackupIdentity.same(copy.hash(), copy.bytes(), copy.hash(), copy.bytes(), copy.live(), false))
                sizes.computeIfAbsent(copy.bytes(), unused -> new ArrayList<>()).add(copy);
        }
    }
    public boolean hasCandidates(long bytes) { return sizes.containsKey(bytes); }
    public Copy match(String name, long bytes, String hash) {
        Copy result = null;
        for (Copy copy : sizes.getOrDefault(bytes, List.of())) {
            if (!BackupIdentity.same(copy.hash(), copy.bytes(), hash, bytes, true, false)) continue;
            if (result == null || !result.name().equalsIgnoreCase(name) && copy.name().equalsIgnoreCase(name)) result = copy;
        }
        return result;
    }
}
