package com.k100pm.tuner.data

import androidx.room.*

@Entity(tableName = "equalizer_presets")
data class EqualizerPreset(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "band_gains") val bandGains: String,         // JSON: [0.5, -1.2, 3.0, ...]
    @ColumnInfo(name = "bass_boost") val bassBoost: Int = 0,       // 0-1000 (per mille)
    @ColumnInfo(name = "virtualizer") val virtualizer: Int = 0,    // 0-1000 (per mille)
    @ColumnInfo(name = "is_builtin") val isBuiltin: Boolean = false // 内置预设不可删除
)

@Dao
interface EqualizerPresetDao {
    @Query("SELECT * FROM equalizer_presets ORDER BY is_builtin DESC, id ASC")
    suspend fun getAll(): List<EqualizerPreset>

    @Insert
    suspend fun insert(preset: EqualizerPreset)

    @Update
    suspend fun update(preset: EqualizerPreset)

    @Delete
    suspend fun delete(preset: EqualizerPreset)

    @Query("SELECT * FROM equalizer_presets WHERE id = :id")
    suspend fun getById(id: Int): EqualizerPreset?

    @Query("SELECT * FROM equalizer_presets WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): EqualizerPreset?
}

/**
 * V4A 调音预设表旧版已移除；新版音效页使用独立 v4a2/data/ViperDatabase（官方2.0.3照搬）。
 */

@Database(
    entities = [EqualizerPreset::class],
    version = 3,
    exportSchema = false
)
abstract class TunerDatabase : RoomDatabase() {
    abstract fun equalizerPresetDao(): EqualizerPresetDao

    companion object {
        @Volatile
        private var INSTANCE: TunerDatabase? = null

        fun getInstance(context: android.content.Context): TunerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TunerDatabase::class.java,
                    "k100pm_tuner.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}