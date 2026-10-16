package app.televault.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Sensitive fields (names, URIs, content keys, metadata) exist only in sealed payload. */
@Entity(tableName="vault_cache")
public class CacheRow {
    @PrimaryKey @NonNull public String id="";
    @NonNull public String vault="";
    public long account;
    @NonNull public String kind="";
    @NonNull public byte[] sealed=new byte[0];
    @NonNull public String phase="";
    public long progress;
    public long total;
    public long retryAt;
    public CacheRow() {}
}
