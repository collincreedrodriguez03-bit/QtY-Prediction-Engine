package com.example

import com.example.data.db.AppDatabase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    @Test
    fun testDatabaseCreationAndDaoOperations() {
        val context = RuntimeEnvironment.getApplication()
        val db = AppDatabase.getDatabase(context)
        assertNotNull(db)

        val dao = db.engineDao()
        assertNotNull(dao)
        val kalshiDao = db.kalshiDao()
        assertNotNull(kalshiDao)
    }

    @Test
    fun testMigrationObjectsNotNull() {
        assertNotNull(AppDatabase.MIGRATION_1_2)
        assertNotNull(AppDatabase.MIGRATION_2_3)
        assertNotNull(AppDatabase.MIGRATION_3_4)
        assertNotNull(AppDatabase.MIGRATION_4_5)
        assertTrue(AppDatabase.MIGRATION_1_2.startVersion == 1)
        assertTrue(AppDatabase.MIGRATION_1_2.endVersion == 2)
        assertTrue(AppDatabase.MIGRATION_2_3.startVersion == 2)
        assertTrue(AppDatabase.MIGRATION_2_3.endVersion == 3)
        assertTrue(AppDatabase.MIGRATION_3_4.startVersion == 3)
        assertTrue(AppDatabase.MIGRATION_3_4.endVersion == 4)
        assertTrue(AppDatabase.MIGRATION_4_5.startVersion == 4)
        assertTrue(AppDatabase.MIGRATION_4_5.endVersion == 5)
    }

    @Test(expected = Exception::class)
    fun testMigrationFailureIsNotSwallowedSilently() {
        // When an invalid database throws on execution, MIGRATION_1_2 must throw rather than silently swallowing
        val mockDb = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        ) { _, method, _ ->
            if (method.name == "execSQL") {
                throw android.database.sqlite.SQLiteException("Simulated disk/SQL corruption")
            }
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        AppDatabase.MIGRATION_1_2.migrate(mockDb)
    }
}
