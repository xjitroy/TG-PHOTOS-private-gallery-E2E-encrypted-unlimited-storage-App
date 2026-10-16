package app.televault.domain;

import java.util.List;

/** Deleting a legacy base descriptor must not make independently created sibling slots disappear. */
public final class VaultDirectoryPolicy {
    private VaultDirectoryPolicy() { }
    public static String choose(String preferred, String baseId, List<String> newestFirstSlots) {
        if (preferred != null && !preferred.isEmpty() && (preferred.equals(baseId) || newestFirstSlots.contains(preferred))) return preferred;
        if (baseId != null && !baseId.isEmpty()) return baseId;
        return newestFirstSlots.isEmpty() ? null : newestFirstSlots.get(0);
    }
}
