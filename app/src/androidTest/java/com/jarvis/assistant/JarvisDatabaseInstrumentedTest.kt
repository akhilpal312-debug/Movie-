package com.jarvis.assistant

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.jarvis.assistant.database.JarvisDatabase
import com.jarvis.assistant.memory.TaskRepository
import com.jarvis.assistant.security.CryptoBox
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the Room schema and encrypted task storage on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class JarvisDatabaseInstrumentedTest {

    private lateinit var database: JarvisDatabase
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JarvisDatabase::class.java,
        ).build()
        repository = TaskRepository(database.taskDao(), CryptoBox())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun storesTitlesEncryptedAndRemovesCompletedTasksFromTheActiveList() = runTest {
        repository.add("pay electricity bill")

        val stored = database.taskDao().activeSnapshot().single()
        assertThat(stored.titleCipher).doesNotContain("electricity")
        assertThat(repository.activeTitles()).containsExactly("pay electricity bill")

        val completed = repository.complete("electricity")
        assertThat(completed).isEqualTo("pay electricity bill")
        assertThat(repository.activeTitles()).isEmpty()
    }

    @Test
    fun completingAnUnknownTaskReportsNoMatch() = runTest {
        repository.add("water the plants")
        assertThat(repository.complete("buy groceries")).isNull()
        assertThat(repository.activeTitles()).hasSize(1)
    }

    @Test
    fun clearingCompletedKeepsActiveTasks() = runTest {
        repository.add("active task")
        repository.add("finished task")
        repository.complete("finished")
        repository.clearCompleted()

        assertThat(repository.activeTitles()).containsExactly("active task")
        val remaining = database.taskDao().activeSnapshot()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().completedAt).isNull()
    }
}
