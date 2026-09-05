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
        AdaptiveCalibrationEntity::class,
        KalshiOrderRecordEntity::class,
        RealizedProfitLedgerEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun engineDao(): EngineDao
    abstract fun kalshiDao(): KalshiDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE predictions ADD COLUMN calibratedScore REAL DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_predictions_timestamp ON predictions(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_predictions_maturityTimestamp ON predictions(maturityTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_predictions_result ON predictions(result)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS kalshi_orders (
                        clientOrderId TEXT NOT NULL PRIMARY KEY,
                        orderId TEXT,
                        ticker TEXT NOT NULL,
                        side TEXT NOT NULL,
                        action TEXT NOT NULL DEFAULT 'buy',
                        requestedCount INTEGER NOT NULL,
                        filledCount INTEGER NOT NULL DEFAULT 0,
                        remainingCount INTEGER NOT NULL,
                        limitPriceCents INTEGER NOT NULL,
                        averageFillPriceCents REAL,
                        feesCents REAL NOT NULL DEFAULT 0.0,
                        lifecycleState TEXT NOT NULL,
                        placedTimestamp INTEGER NOT NULL,
                        updatedTimestamp INTEGER NOT NULL,
                        failureReason TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_kalshi_orders_clientOrderId ON kalshi_orders(clientOrderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_kalshi_orders_orderId ON kalshi_orders(orderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_kalshi_orders_ticker ON kalshi_orders(ticker)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_kalshi_orders_lifecycleState ON kalshi_orders(lifecycleState)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS realized_profit_ledger (
                        tradeId TEXT NOT NULL PRIMARY KEY,
                        contractTicker TEXT NOT NULL,
                        orderId TEXT NOT NULL,
                        clientOrderId TEXT NOT NULL,
                        entryCostDollars REAL NOT NULL,
                        settlementPriceDollars REAL NOT NULL,
                        feesDollars REAL NOT NULL,
                        realizedPnlDollars REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        capitalSource TEXT NOT NULL,
                        eligibleNextTradeCapitalDollars REAL NOT NULL,
                        isWin INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_realized_profit_ledger_tradeId ON realized_profit_ledger(tradeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_realized_profit_ledger_contractTicker ON realized_profit_ledger(contractTicker)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_realized_profit_ledger_timestamp ON realized_profit_ledger(timestamp)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "qty_telemetry_database.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
