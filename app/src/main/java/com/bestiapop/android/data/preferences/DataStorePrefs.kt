package com.bestiapop.android.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit

suspend fun <T> DataStore<Preferences>.put(key: Preferences.Key<T>, value: T) {
    edit { it[key] = value }
}
