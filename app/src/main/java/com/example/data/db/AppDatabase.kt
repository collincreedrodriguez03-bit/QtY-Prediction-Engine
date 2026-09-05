package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EngineCycleEntity::class,
        PredictionEntity::class,
        BacktestRecordEntity::class,
        AdaptiveCalibrationEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun engineDao(): EngineDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE predictions ADD COLUMN calibratedScore REAL DEFAULT NULL")
                } catch (e: Exception) {
                    // Column might already exist in some builds
                }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_predictions_timestamp ON predictions(timestamp)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_predictions_maturityTimestamp ON predictions(maturityTimestamp)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_predictions_result ON predictions(result)")
                } catch (e: Exception) {
                    // Indices may already exist
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE predictions ADD COLUMN volume REAL NOT NULL DEFAULT 0.0")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN bidAskSpread REAL NOT NULL DEFAULT 0.0")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN exchangeAgreement TEXT NOT NULL DEFAULT 'STRONG_AGREEMENT'")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN settlementReference REAL NOT NULL DEFAULT 0.0")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN settlementMethodology TEXT NOT NULL DEFAULT '15M_ROLLING_WINDOW'")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN projectedPrice90s REAL NOT NULL DEFAULT 0.0")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN projectedDecision90s TEXT NOT NULL DEFAULT 'NO-TRADE'")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN actualPrice30s REAL DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN result30s TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN maturityTimestamp90s INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN actualPrice90s REAL DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN result90s TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN sourceExchange TEXT NOT NULL DEFAULT 'CONSOLIDATED_USD'")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN marketTimestamp INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN kalshiContractTicker TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN strikePrice REAL DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN kalshiOrderId TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN kalshiOrderStatus TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN kalshiFilledCount INTEGER DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN kalshiOrderPrice INTEGER DEFAULT NULL")
                    db.execSQL("ALTER TABLE predictions ADD COLUMN executionPrice REAL DEFAULT NULL")
                } catch (e: Exception) {
                    // Migration safety
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "qty_telemetry_database.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
