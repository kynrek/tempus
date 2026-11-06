package com.eddyizm.tempus.database.index;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/** Key/value facts about the index, such as which server and music folder it was built from. */
@Entity(tableName = "index_meta")
public class IndexMeta {
    @PrimaryKey
    @NonNull
    public String key = "";
    public String value;

    public IndexMeta() {
    }

    @Ignore
    public IndexMeta(@NonNull String key, String value) {
        this.key = key;
        this.value = value;
    }
}
