package com.rassul.audioeditor.media

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.pipeline.SpaceEstimator
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/*
 * Зона MEDIA, T17. FR-1: Photo Picker (только видео), ACTION_OPEN_DOCUMENT (аудио и видео), SEND и VIEW.
 * Разрешений на хранилище не запрашиваем: доступ — только по выданным URI.
 */

/** Откуда пришёл файл: из выбора в приложении или извне (для FR-31). */
data class IncomingSource(val uri: Uri, val fromShare: Boolean, val mimeHint: String?)

/** Открытый источник: дескриптор (seekable) и адаптер для :core. Закрывать — close() (удалит копию в кэше). */
class OpenedSource internal constructor(
    val uri: Uri,
    val displayName: String,
    val mime: String?,
    private val pfd: ParcelFileDescriptor,
    /** Копия во временной папке, если исходный поток не поддерживал произвольный доступ. */
    val tempCopy: File?,
) : Closeable {
    val fileDescriptor: FileDescriptor get() = pfd.fileDescriptor
    val source: FdSource = FdSource(pfd.fileDescriptor)
    val sizeBytes: Long get() = source.size
    /** Имя без расширения — основа имён результатов (FR-27). */
    val baseName: String get() = displayName.substringBeforeLast('.', displayName)

    @Volatile private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        try { pfd.close() } catch (_: Exception) {}
        tempCopy?.delete()
    }
}

object SourceOpener {
    const val REQUEST_PICK_VIDEO = 7101
    const val REQUEST_OPEN_DOCUMENT = 7102
    private const val COPY_DIR = "src_copy"

    /** Photo Picker, только видео (FR-1). */
    fun pickVideoIntent(): Intent = Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*")

    /** Системный выбор файлов: аудио и видео (FR-1). */
    fun openDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))

    @Suppress("DEPRECATION")
    fun launchPickVideo(activity: Activity) = activity.startActivityForResult(pickVideoIntent(), REQUEST_PICK_VIDEO)

    @Suppress("DEPRECATION")
    fun launchOpenDocument(activity: Activity) = activity.startActivityForResult(openDocumentIntent(), REQUEST_OPEN_DOCUMENT)

    /**
     * Разобрать результат onActivityResult. null — не наш код запроса или отмена.
     * Для OPEN_DOCUMENT пытается сохранить постоянное разрешение (чтобы «Сохранённые»/повторное открытие работали).
     */
    fun onActivityResult(ctx: Context, requestCode: Int, resultCode: Int, data: Intent?): IncomingSource? {
        if (requestCode != REQUEST_PICK_VIDEO && requestCode != REQUEST_OPEN_DOCUMENT) return null
        if (resultCode != Activity.RESULT_OK) return null
        val uri = data?.data ?: return null
        takePersistable(ctx, uri, data.flags)
        return IncomingSource(uri, fromShare = false, mimeHint = data.type)
    }

    /** VIEW (data) или SEND (EXTRA_STREAM) от другого приложения; null — интент не про файл. */
    fun fromIntent(intent: Intent?): IncomingSource? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { IncomingSource(it, true, intent.type) }
            Intent.ACTION_SEND -> (intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: intent.data)
                ?.let { IncomingSource(it, true, intent.type) }
            else -> null
        }
    }

    /** takePersistableUriPermission, где это возможно (молча пропускает, если провайдер не даёт). */
    @Suppress("UNUSED_PARAMETER")
    fun takePersistable(ctx: Context, uri: Uri, flags: Int) {
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
    }

    /** Имя файла для показа (OpenableColumns.DISPLAY_NAME), иначе последний сегмент пути. */
    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "audio"
    }

    /**
     * Открыть URI для чтения с произвольным доступом. Долгая операция (возможна копия) — НЕ из UI-потока.
     * Ошибки — CoreException с понятным текстом.
     */
    fun open(ctx: Context, uri: Uri, progress: Progress = Progress.NONE): OpenedSource {
        val cr = ctx.contentResolver
        val name = displayName(ctx, uri)
        val mime = try { cr.getType(uri) } catch (_: Exception) { null }
        val pfd = try { cr.openFileDescriptor(uri, "r") } catch (e: SecurityException) {
            throw CoreException("Нет доступа к файлу. Откройте его ещё раз через «Файлы» или «Галерею».", e)
        } catch (e: java.io.FileNotFoundException) {
            throw CoreException("Файл не найден: возможно, он удалён или перемещён.", e)
        } catch (e: Exception) {
            throw CoreException("Не удалось открыть файл.", e)
        } ?: throw CoreException("Не удалось открыть файл.")
        if (FdSource.isSeekable(pfd)) return OpenedSource(uri, name, mime, pfd, null)
        // Поток без произвольного доступа (некоторые провайдеры «Поделиться»): копия в cacheDir.
        try { pfd.close() } catch (_: Exception) {}
        val copy = copyToCache(ctx, cr, uri, progress)
        val cpfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
        return OpenedSource(uri, name, mime, cpfd, copy)
    }

    private fun copyToCache(ctx: Context, cr: ContentResolver, uri: Uri, progress: Progress): File {
        val dir = File(ctx.cacheDir, COPY_DIR).apply { mkdirs() }
        val out = File.createTempFile("src", ".bin", dir)
        val declared = try { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
        if (declared > 0) SpaceEstimator.check(declared + SpaceEstimator.MARGIN_BYTES, StatFs(dir.path).availableBytes)
            ?.let { out.delete(); throw CoreException(it) }
        try {
            val input = cr.openInputStream(uri) ?: throw CoreException("Не удалось прочитать файл.")
            input.use { inp ->
                FileOutputStream(out).use { os ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        if (progress.isCancelled) throw CancelledException()
                        val r = inp.read(buf)
                        if (r < 0) break
                        os.write(buf, 0, r)
                        done += r
                        if (declared > 0) progress.report(done.toDouble() / declared)
                    }
                }
            }
            return out
        } catch (e: CoreException) {
            out.delete(); throw e
        } catch (e: java.io.IOException) {
            out.delete()
            val msg = e.message ?: ""
            throw CoreException(if (msg.contains("ENOSPC") || msg.contains("No space")) "Недостаточно места для временной копии файла." else "Не удалось прочитать файл.", e)
        }
    }

    /** Удалить временные копии от прошлых запусков (вызывать при старте приложения). */
    fun cleanupStale(ctx: Context) {
        val t0 = startedAt; File(ctx.cacheDir, COPY_DIR).listFiles()?.forEach { if (it.lastModified() < t0) it.delete() }
    }
}
