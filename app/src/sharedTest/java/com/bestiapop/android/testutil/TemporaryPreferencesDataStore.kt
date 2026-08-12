package com.bestiapop.android.testutil

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin

/**
 * Owns an isolated on-disk Preferences DataStore that can be closed and reopened to model a
 * process restart. Each instance uses a unique directory and removes it during [close].
 */
class TemporaryPreferencesDataStore(
    context: Context,
    name: String = "preferences"
) {
    private val directory = File(
        context.cacheDir,
        "$name-${UUID.randomUUID()}"
    ).apply { check(mkdirs()) }
    private val file = File(directory, "$name.preferences_pb")
    private var job: CompletableJob = SupervisorJob()

    var dataStore: DataStore<Preferences> = create()
        private set

    suspend fun restart() {
        job.cancelAndJoin()
        job = SupervisorJob()
        dataStore = create()
    }

    suspend fun close() {
        job.cancelAndJoin()
        check(directory.deleteRecursively()) {
            "Could not delete temporary DataStore directory: $directory"
        }
    }

    private fun create(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { file }
        )
}
