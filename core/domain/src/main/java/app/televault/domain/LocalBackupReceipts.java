package app.televault.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Local, authenticated content-verification receipts only; cloud source tokens are hints. */
public final class LocalBackupReceipts {
    private LocalBackupReceipts() { }

    public static Map<String, String> retained(Set<String> retainedFileIds, Map<String, String> localReceipts) {
        Map<String, String> result = new LinkedHashMap<>();
        localReceipts.forEach((source, file) -> {
            if (source != null && !source.trim().isEmpty() && file != null && retainedFileIds.contains(file)) {
                result.put(source, file);
            }
        });
        return result;
    }
}
