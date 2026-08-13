package com.bestiapop.android.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.bestiapop.android.data.network.HttpClients
import com.bestiapop.android.data.util.copyTransferToFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

internal fun interface ApkValidator {
    fun validate(apk: File): Result<Unit>
}

internal class PackageManagerApkValidator(context: Context) : ApkValidator {
    private val packageManager = context.packageManager
    private val expectedPackageName = context.packageName

    @Suppress("DEPRECATION")
    override fun validate(apk: File): Result<Unit> = runCatching {
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        } ?: error("El archivo descargado no es un APK válido")
        check(archive.packageName == expectedPackageName) {
            "El APK descargado pertenece a otra aplicación"
        }
    }
}

internal class ApkUpdateDownloader(
    private val http: OkHttpClient,
    private val validator: ApkValidator
) {
    suspend fun download(
        url: String,
        dest: File,
        userAgent: String,
        onProgress: (Float?) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val part = File(dest.parentFile, "${dest.name}.part")
        try {
            dest.parentFile?.mkdirs()
            part.delete()
            dest.delete()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Descarga APK HTTP ${response.code}" }
                val body = response.body ?: error("Descarga APK vacía")
                val expectedBytes = body.contentLength()
                val copiedBytes = body.byteStream().use { input ->
                    val transferContext = currentCoroutineContext()
                    copyTransferToFile(
                        input = input,
                        destination = part,
                        syncToDisk = true,
                        checkCancelled = transferContext::ensureActive
                    ) { copied ->
                        onProgress(
                            if (expectedBytes >= 0L) {
                                (copied.toFloat() / expectedBytes.toFloat()).coerceAtMost(1f)
                            } else {
                                null
                            }
                        )
                    }
                }
                check(copiedBytes > 0L) { "Descarga APK vacía" }
                check(expectedBytes < 0L || copiedBytes == expectedBytes) {
                    "Descarga APK incompleta: $copiedBytes de $expectedBytes bytes"
                }
            }
            ensureActive()
            validator.validate(part).getOrThrow()
            ensureActive()
            Files.move(
                part.toPath(),
                dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            ensureActive()
            Result.success(dest)
        } catch (e: CancellationException) {
            part.delete()
            dest.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            dest.delete()
            Result.failure(e)
        }
    }
}

object ApkUpdateInstaller {

    private const val PROVIDER_SUFFIX = ".fileprovider"
    private const val RELATIVE_DIR = "updates"
    private const val APK_NAME = "BestiaPop-update.apk"

    private val downloadClient: OkHttpClient = HttpClients.transfer

    fun updateFile(context: Context): File =
        File(File(context.cacheDir, RELATIVE_DIR), APK_NAME)

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(
            "package:${context.packageName}".toUri()
        )

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + PROVIDER_SUFFIX,
            apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun download(
        context: Context,
        url: String,
        dest: File,
        userAgent: String,
        onProgress: (Float?) -> Unit
    ): Result<File> = ApkUpdateDownloader(
        http = downloadClient,
        validator = PackageManagerApkValidator(context)
    ).download(url, dest, userAgent, onProgress)
}
