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
    }

    @Test
    fun testMigrationObjectsNotNull() {
        assertNotNull(AppDatabase.MIGRATION_1_2)
        assertNotNull(AppDatabase.MIGRATION_2_3)
        assertTrue(AppDatabase.MIGRATION_1_2.startVersion == 1)
        assertTrue(AppDatabase.MIGRATION_1_2.endVersion == 2)
        assertTrue(AppDatabase.MIGRATION_2_3.startVersion == 2)
        assertTrue(AppDatabase.MIGRATION_2_3.endVersion == 3)
    }
}
