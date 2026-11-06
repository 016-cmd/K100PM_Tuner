package com.k100pm.tuner.music

import android.content.Context
import androidx.room.*

/**
 * 收藏歌曲 Room 数据库
 */
@Entity(tableName = "favorite_songs")
data class FavoriteSong(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0
)

@Dao
interface FavoriteSongDao {
    @Query("SELECT * FROM favorite_songs ORDER BY sort_order ASC, added_at DESC")
    suspend fun getAll(): List<FavoriteSong>

    @Query("SELECT * FROM favorite_songs WHERE song_id = :songId AND source = :source LIMIT 1")
    suspend fun getBySongId(songId: String, source: String): FavoriteSong?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: FavoriteSong)

    @Update
    suspend fun update(song: FavoriteSong)

    @Delete
    suspend fun delete(song: FavoriteSong)

    @Query("DELETE FROM favorite_songs WHERE song_id = :songId AND source = :source")
    suspend fun deleteBySongId(songId: String, source: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_songs WHERE song_id = :songId AND source = :source)")
    suspend fun isFavorite(songId: String, source: String): Boolean

    @Query("UPDATE favorite_songs SET sort_order = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Int, order: Int)

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM favorite_songs")
    suspend fun getMaxSortOrder(): Int
}

@Database(
    entities = [FavoriteSong::class],
    version = 1,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun favoriteSongDao(): FavoriteSongDao

    companion object {
        @Volatile private var INSTANCE: MusicDatabase? = null

        fun getInstance(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "k100pm_music.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
