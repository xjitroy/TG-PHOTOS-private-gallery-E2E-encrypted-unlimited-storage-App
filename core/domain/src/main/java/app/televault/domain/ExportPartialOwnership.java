package app.televault.domain;

import java.util.UUID;

/** Exact ownership proof for canceling one app-created, unpublished export destination. */
public final class ExportPartialOwnership {
    private ExportPartialOwnership() {}
    public static boolean mayDelete(String exportId, String owner, String uri, String ownedUri,
                                    String name, String ownedName, String finalName, boolean completed) {
        if (completed || exportId == null || uri == null || name == null || finalName == null) return false;
        try {
            if (!UUID.fromString(exportId).toString().equals(exportId)) return false;
        } catch (IllegalArgumentException ignored) { return false; }
        return exportId.equals(owner) && !uri.isBlank() && uri.equals(ownedUri)
            && !name.isBlank() && name.equals(ownedName) && !finalName.isBlank()
            && !name.equals(finalName);
    }
}
