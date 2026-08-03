package com.bestiapop.android.data.util

import android.content.Context
import android.os.Environment
import java.io.File

object StorageUtils {

    /**
     * Public storage location for downloaded/uploaded music.
     * Saved under /storage/emulated/0/Music/BestiaPop/
     * This directory is PERMANENT and is NOT deleted when the app is uninstalled.
     */
    fun getPublicMusicDirectory(context: Context): File {
        return try {
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val bestiaPopDir = File(publicDir, "BestiaPop")
            if (!bestiaPopDir.exists()) {
                bestiaPopDir.mkdirs()
            }
            if (bestiaPopDir.exists()) {
                bestiaPopDir
            } else {
                val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "BestiaPop")
                if (!fallback.exists()) fallback.mkdirs()
                fallback
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "BestiaPop")
            if (!fallback.exists()) fallback.mkdirs()
            fallback
        }
    }
}
