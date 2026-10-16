package app.televault.data;

import androidx.room.*;
import java.util.List;

@Dao
public interface CacheDao {
    @Query("SELECT * FROM vault_cache WHERE account=:account AND vault=:vault AND kind=:kind")
    List<CacheRow> list(long account, String vault, String kind);
    @Query("SELECT * FROM vault_cache WHERE account=:account AND vault=:vault AND kind='job' AND (phase NOT IN ('COMPLETE','CANCELLED') OR id IN (SELECT id FROM vault_cache WHERE account=:account AND vault=:vault AND kind='job' AND phase IN ('COMPLETE','CANCELLED') ORDER BY rowid DESC LIMIT 100))")
    List<CacheRow> transfers(long account, String vault);
    @Query("SELECT id FROM vault_cache WHERE account=:account AND vault=:vault AND kind=:kind")
    List<String> ids(long account, String vault, String kind);
    @Query("SELECT * FROM vault_cache WHERE account=:account AND vault=:vault AND kind='job' AND phase NOT IN ('COMPLETE','CANCELLED')")
    List<CacheRow> activeJobs(long account, String vault);
    @Query("SELECT * FROM vault_cache WHERE id=:id") CacheRow get(String id);
    @Insert(onConflict=OnConflictStrategy.REPLACE) void put(CacheRow row);
    @Query("UPDATE vault_cache SET phase=:phase, progress=:progress, total=:total, retryAt=:retryAt WHERE id=:id AND kind='job' AND phase!='CANCELLED'")
    int updateTransfer(String id, String phase, long progress, long total, long retryAt);
    @Query("UPDATE vault_cache SET sealed=:sealed, phase=:phase, progress=:progress, total=:total, retryAt=:retryAt WHERE id=:id AND kind='job' AND phase!='CANCELLED'")
    int updateJobPayload(String id, byte[] sealed, String phase, long progress, long total, long retryAt);
    @Query("DELETE FROM vault_cache WHERE kind='job' AND phase IN ('COMPLETE','CANCELLED') AND id NOT IN (SELECT id FROM vault_cache WHERE kind='job' AND phase IN ('COMPLETE','CANCELLED') ORDER BY rowid DESC LIMIT 200)")
    void pruneCompletedJobs();
    @Query("UPDATE vault_cache SET phase=:phase, progress=:progress, total=:total WHERE id=:id AND kind='cloud-export' AND phase!='CANCELLED'")
    int exportProgress(String id, String phase, long progress, long total);
    @Query("UPDATE vault_cache SET sealed=:sealed, phase=:phase, progress=:progress, total=:total WHERE id=:id AND kind='cloud-export' AND phase!='CANCELLED'")
    int updateExportPayload(String id, byte[] sealed, String phase, long progress, long total);
    @Query("UPDATE vault_cache SET phase='CANCELLED' WHERE id=:id AND account=:account AND vault=:vault AND kind='cloud-export' AND phase!='COMPLETE'")
    int cancelExport(String id, long account, String vault);
    @Query("DELETE FROM vault_cache WHERE account=:account AND vault=:vault AND kind='export-entry' AND id LIKE :exportId || ':entry:%'")
    void removeExportEntries(long account, String vault, String exportId);
    @Query("DELETE FROM vault_cache WHERE id=:id") void remove(String id);
    @Query("DELETE FROM vault_cache WHERE account=:account AND vault=:vault") void removeVault(long account, String vault);
}
