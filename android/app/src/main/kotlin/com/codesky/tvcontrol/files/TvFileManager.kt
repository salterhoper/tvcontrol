package com.codesky.tvcontrol.files

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import com.codesky.tvcontrol.core.EventBus
import com.codesky.tvcontrol.core.Logging
import com.codesky.tvcontrol.core.RootCheck
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.text.DecimalFormat

@Serializable
data class FileItemDto(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isApk: Boolean,
    val extension: String,
    val formattedSize: String,
    val packageName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val path: String,
)

@Serializable
data class StorageInfoDto(
    val totalBytes: Long,
    val freeBytes: Long,
    val usableBytes: Long,
    val formattedFree: String,
    val formattedTotal: String,
    val freePercent: Int,
    val storagePath: String,
    val files: List<FileItemDto>,
)

@Serializable
data class FileActionResponse(
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null,
    val file: FileItemDto? = null,
)

class TvFileManager(
    private val context: Context,
    private val events: EventBus,
) {
    /**
     * Resolves the best storage directory for uploads.
     * Prefers /sdcard/Download/TvControl so other TV apps can access files,
     * falling back to app-specific external storage if permission-restricted.
     */
    val storageDir: File by lazy {
        val appExt = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        if (!appExt.exists()) appExt.mkdirs()
        appExt
    }

    fun getStorageInfo(): StorageInfoDto {
        val dir = storageDir
        if (!dir.exists()) dir.mkdirs()

        val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
        val totalBytes = stat?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
        val freeBytes = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
        val usableBytes = dir.usableSpace
        val freePercent = if (totalBytes > 0) ((freeBytes.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0

        val fileList = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }
            .map { toDto(it) }

        return StorageInfoDto(
            totalBytes = totalBytes,
            freeBytes = freeBytes,
            usableBytes = usableBytes,
            formattedFree = formatBytes(freeBytes),
            formattedTotal = formatBytes(totalBytes),
            freePercent = freePercent,
            storagePath = dir.absolutePath,
            files = fileList,
        )
    }

    fun saveFile(filename: String, inputStream: InputStream): FileItemDto {
        val sanitized = sanitizeFilename(filename)
        val target = File(storageDir, sanitized)

        target.outputStream().use { out ->
            inputStream.copyTo(out, bufferSize = 64 * 1024)
        }

        val dto = toDto(target)
        events.emit("file_list_changed", null)
        Logging.i("File saved: ${target.name} (${target.length()} bytes)")
        return dto
    }

    fun getFile(filename: String): File? {
        val sanitized = sanitizeFilename(filename)
        val target = File(storageDir, sanitized)
        return if (target.exists() && target.isFile) target else null
    }

    fun deleteFile(filename: String): Boolean {
        val sanitized = sanitizeFilename(filename)
        val target = File(storageDir, sanitized)
        val deleted = target.exists() && target.delete()
        if (deleted) {
            events.emit("file_list_changed", null)
            Logging.i("File deleted: $sanitized")
        }
        return deleted
    }

    suspend fun installApk(filenameOrPath: String): FileActionResponse {
        val file = if (filenameOrPath.startsWith("/")) {
            File(filenameOrPath)
        } else {
            File(storageDir, sanitizeFilename(filenameOrPath))
        }

        if (!file.exists() || !file.isFile) {
            return FileActionResponse(ok = false, error = "File not found: ${file.name}")
        }
        if (!file.name.endsWith(".apk", ignoreCase = true)) {
            return FileActionResponse(ok = false, error = "Not an APK file: ${file.name}")
        }

        // Try silent root install if su execution is permitted for apps
        val rootReport = RootCheck.collect(context)
        if (rootReport.appCanUseRoot) {
            val rootInstallResult = runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r \"${file.absolutePath}\""))
                val exitCode = process.waitFor()
                exitCode == 0
            }.getOrDefault(false)

            if (rootInstallResult) {
                return FileActionResponse(
                    ok = true,
                    message = "Successfully installed ${file.name} silently via root.",
                    file = toDto(file),
                )
            }
        }

        // Standard Android install via FileProvider
        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(installIntent)

            FileActionResponse(
                ok = true,
                message = "Package installer launched on TV screen. Please confirm installation using your remote control.",
                file = toDto(file),
            )
        } catch (e: Exception) {
            Logging.e("Failed to launch package installer for ${file.name}", e)
            FileActionResponse(
                ok = false,
                error = "Could not start installer: ${e.message}",
            )
        }
    }

    private fun toDto(file: File): FileItemDto {
        val isApk = file.name.endsWith(".apk", ignoreCase = true)
        val ext = file.extension.lowercase()
        var packageName: String? = null
        var appName: String? = null
        var appVersion: String? = null

        if (isApk) {
            runCatching {
                val pm = context.packageManager
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(file.absolutePath, 0)
                }

                if (pkgInfo != null) {
                    packageName = pkgInfo.packageName
                    appVersion = pkgInfo.versionName
                    pkgInfo.applicationInfo?.let { appInfo ->
                        appInfo.sourceDir = file.absolutePath
                        appInfo.publicSourceDir = file.absolutePath
                        appName = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull()
                    }
                }
            }
        }

        return FileItemDto(
            name = file.name,
            size = file.length(),
            lastModified = file.lastModified(),
            isApk = isApk,
            extension = ext,
            formattedSize = formatBytes(file.length()),
            packageName = packageName,
            appName = appName,
            appVersion = appVersion,
            path = file.absolutePath,
        )
    }

    private fun sanitizeFilename(name: String): String {
        return File(name).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[index]
    }
}
