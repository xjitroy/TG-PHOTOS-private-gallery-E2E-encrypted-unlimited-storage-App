package app.televault.data;

import androidx.room.*;

@Database(entities={CacheRow.class},version=1,exportSchema=true)
public abstract class DriveDatabase extends RoomDatabase {
    public abstract CacheDao cache();
}
