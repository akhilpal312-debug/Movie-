package com.jarvis.assistant.core.di

import android.content.Context
import androidx.room.Room
import com.jarvis.assistant.ai.GeminiLiveClient
import com.jarvis.assistant.ai.LiveTokenService
import com.jarvis.assistant.core.ActionRunner
import com.jarvis.assistant.database.AuditDao
import com.jarvis.assistant.database.HistoryDao
import com.jarvis.assistant.database.JarvisDatabase
import com.jarvis.assistant.database.LocalAuditSink
import com.jarvis.assistant.database.MemoryDao
import com.jarvis.assistant.database.RoutineDao
import com.jarvis.assistant.database.TaskDao
import com.jarvis.assistant.device.ActionExecutor
import com.jarvis.assistant.security.AndroidPermissionGate
import com.jarvis.assistant.security.AuditSink
import com.jarvis.assistant.security.CryptoBox
import com.jarvis.assistant.security.JarvisPrivacyCore
import com.jarvis.assistant.security.PermissionGate
import com.jarvis.assistant.security.SecretCipher
import com.jarvis.assistant.security.SensitiveContentScanner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, JarvisDatabase.NAME)
            // Schema version 1. Any future change ships with an explicit migration so
            // user memories are never dropped silently.
            .build()

    @Provides fun memoryDao(db: JarvisDatabase): MemoryDao = db.memoryDao()
    @Provides fun taskDao(db: JarvisDatabase): TaskDao = db.taskDao()
    @Provides fun routineDao(db: JarvisDatabase): RoutineDao = db.routineDao()
    @Provides fun historyDao(db: JarvisDatabase): HistoryDao = db.historyDao()
    @Provides fun auditDao(db: JarvisDatabase): AuditDao = db.auditDao()

    /**
     * One client for Gemini Live and the JARVIS backend. Reads have a short timeout so
     * a broken connection fails fast instead of leaving a listening indicator stuck.
     */
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived websocket
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun sensitiveScanner(): SensitiveContentScanner = SensitiveContentScanner()

    /**
     * Built manually so the audit sink and clock stay explicit constructor
     * dependencies without confusing Dagger's constructor-injection analysis.
     */
    @Provides
    @Singleton
    fun privacyCore(auditSink: AuditSink): JarvisPrivacyCore = JarvisPrivacyCore(auditSink)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun permissionGate(impl: AndroidPermissionGate): PermissionGate

    @Binds
    @Singleton
    abstract fun secretCipher(impl: CryptoBox): SecretCipher

    @Binds
    @Singleton
    abstract fun auditSink(impl: LocalAuditSink): AuditSink

    @Binds
    @Singleton
    abstract fun actionRunner(impl: ActionExecutor): ActionRunner
}
