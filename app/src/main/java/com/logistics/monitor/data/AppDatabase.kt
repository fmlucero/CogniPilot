package com.logistics.monitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.logistics.monitor.data.dao.EventoOfflineDao
import com.logistics.monitor.data.dao.ReglaDao
import com.logistics.monitor.data.dao.RutaDao
import com.logistics.monitor.data.entities.EventoOfflineEntity
import com.logistics.monitor.data.entities.ParadaEntity
import com.logistics.monitor.data.entities.PaqueteEntity
import com.logistics.monitor.data.entities.ReglaEntity
import com.logistics.monitor.data.entities.RutaEntity

@Database(
    entities = [
        RutaEntity::class,
        ParadaEntity::class,
        PaqueteEntity::class,
        ReglaEntity::class,
        EventoOfflineEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun rutaDao(): RutaDao
    abstract fun reglaDao(): ReglaDao
    abstract fun eventoOfflineDao(): EventoOfflineDao

    companion object {
        private const val DB_NAME = "cognipilot.db"

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
