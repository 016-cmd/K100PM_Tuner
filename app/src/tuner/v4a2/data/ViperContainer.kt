package com.k100pm.tuner.v4a2.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.k100pm.tuner.v4a2.data.dao.DeviceSettingsDao
import com.k100pm.tuner.v4a2.data.dao.DsPresetDao
import com.k100pm.tuner.v4a2.data.dao.EqPresetDao
import com.k100pm.tuner.v4a2.data.dao.PresetDao
import com.k100pm.tuner.v4a2.data.db.ViperDatabase
import com.k100pm.tuner.v4a2.data.model.DsPreset
import com.k100pm.tuner.v4a2.data.model.EqPreset
import com.k100pm.tuner.v4a2.data.repository.ViperRepository
import com.k100pm.tuner.v4a2.viper.ViperDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 手动依赖容器（替代官方 Hilt AppModule）：
 * 懒加载提供 DataStore / ViperDatabase / DAO / ViperRepository。
 * 由宿主在 Application onCreate 时 init() 一次。
 */
object ViperContainer {

    @Volatile private var db: ViperDatabase? = null
    @Volatile private var repository: ViperRepository? = null
    private var dataStore: DataStore<Preferences>? = null

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "viper_preferences")

    fun init(context: Context) {
        val appCtx = context.applicationContext
        unsealHiddenApi()
        initializeDatabase(appCtx)
    }

    /** AudioEffect 反射需要豁免 hidden API（照搬官方 ViPER4AndroidApp init）。 */
    private fun unsealHiddenApi() {
        try {
            val runtime = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = runtime.getDeclaredMethod("getRuntime")
            val setExemptions = runtime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            val rt = getRuntime.invoke(null)
            setExemptions.invoke(rt, arrayOf("L"))
        } catch (_: Throwable) {
            // 反射失败不致命（仅 AudioEffect 反射受限时影响）
        }
    }

    @Synchronized
    private fun initializeDatabase(appCtx: Context) {
        if (db != null) return
        val built =
            Room
                .databaseBuilder(appCtx, ViperDatabase::class.java, "viper4android.db")
                .addMigrations(
                    ViperDatabase.MIGRATION_1_2,
                    ViperDatabase.MIGRATION_2_3,
                    ViperDatabase.MIGRATION_3_4,
                    ViperDatabase.MIGRATION_4_5,
                    ViperDatabase.MIGRATION_5_6,
                )
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onCreate(sqDb: SupportSQLiteDatabase) {
                            super.onCreate(sqDb)
                            val database = this@ViperContainer.db ?: return@onCreate
                            CoroutineScope(Dispatchers.IO).launch {
                                seedEqPresets(database.eqPresetDao())
                                seedDsPresets(database.dsPresetDao())
                            }
                        }

                        override fun onOpen(sqDb: SupportSQLiteDatabase) {
                            super.onOpen(sqDb)
                            val database = this@ViperContainer.db ?: return@onOpen
                            CoroutineScope(Dispatchers.IO).launch {
                                val eqDao = database.eqPresetDao()
                                if (eqDao.countBuiltins() == 0) seedEqPresets(eqDao)
                                val dsDao = database.dsPresetDao()
                                if (dsDao.countBuiltins() == 0) seedDsPresets(dsDao)
                            }
                        }
                    },
                )
                .build()
        db = built
        dataStore = appCtx.dataStore
    }

    private suspend fun seedEqPresets(dao: EqPresetDao) {
        val presets = mutableListOf<EqPreset>()
        for (builtin in ViperDispatcher.BUILTIN_EQ_PRESETS) {
            val bandsByCount =
                mapOf(
                    10 to builtin.bands10,
                    15 to builtin.bands15,
                    25 to builtin.bands25,
                    31 to builtin.bands31,
                )
            for ((bandCount, bands) in bandsByCount) {
                presets.add(
                    EqPreset(
                        name = builtin.key,
                        nameKey = builtin.key,
                        bandCount = bandCount,
                        bands = bands,
                    ),
                )
            }
        }
        dao.insertAll(presets)
    }

    private suspend fun seedDsPresets(dao: DsPresetDao) {
        val presets =
            ViperDispatcher.BUILTIN_DS_PRESETS.map { builtin ->
                DsPreset(
                    name = builtin.key,
                    nameKey = builtin.key,
                    xLow = builtin.xLow,
                    xHigh = builtin.xHigh,
                    yLow = builtin.yLow,
                    yHigh = builtin.yHigh,
                    sideGainLow = builtin.sideGainLow,
                    sideGainHigh = builtin.sideGainHigh,
                )
            }
        dao.insertAll(presets)
    }

    fun database(): ViperDatabase = checkNotNull(db) { "ViperContainer not initialized" }

    fun presetDao(): PresetDao = database().presetDao()

    fun eqPresetDao(): EqPresetDao = database().eqPresetDao()

    fun dsPresetDao(): DsPresetDao = database().dsPresetDao()

    fun deviceSettingsDao(): DeviceSettingsDao = database().deviceSettingsDao()

    fun dataStorePrefs(): DataStore<Preferences> =
        checkNotNull(dataStore) { "ViperContainer not initialized" }

    fun repository(): ViperRepository {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val r = ViperRepository(presetDao(), eqPresetDao(), dsPresetDao(), deviceSettingsDao(), dataStorePrefs())
            repository = r
            return r
        }
    }
}