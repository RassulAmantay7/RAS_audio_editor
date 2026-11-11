package com.rassul.audioeditor.store

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.pipeline.FileNaming
import com.rassul.audioeditor.media.FileChannelSink
import com.rassul.audioeditor.media.OutputTarget
import java.io.FileDescriptor

/*
 * Зона MEDIA, T18. FR-27: сохранение новым файлом в Music/RAS audio editor/ через MediaStore без разрешений:
 * insert(IS_PENDING = 1) → запись через «rw»-дескриптор (seek для заголовков) → IS_PENDING = 0.
 * При ошибке или отмене запись удаляется (FR-29: «Отмена удаляет недописанный файл»).
 * Имя: FileNaming по своему списку (SavedRepository); MediaStore может сам добавить « (1)» — фактическое имя
 * перечитывается после вставки.
 */
class MediaStoreSaver(ctx: Context) {
    private val app = ctx.applicationContext
    private val cr = app.contentResolver

    /** Незавершённый файл. */
    inner class Pending internal constructor(val uri: Uri, val displayName: String, private val pfd: ParcelFileDescriptor) : OutputTarget {
        private val chSink = FileChannelSink(pfd.fileDescriptor)
        override val sink: SeekableSink get() = chSink
        override val fd: FileDescriptor get() = pfd.fileDescriptor
        internal var closed = false
        internal fun closeFd() {
            if (closed) return
            closed = true
            try { chSink.truncateToSize() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    class Saved(val uri: Uri, val displayName: String, val sizeBytes: Long)

    /** Создать запись: имя «<base>_<suffix>.<ext>» (+ « (2)» при занятости). Не из UI-потока. */
    fun create(baseName: String, suffix: String, ext: String, mime: String, existing: Set<String>): Pending {
        val name = FileNaming.next(baseName, suffix, ext, existing)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try { cr.insert(coll, values) } catch (e: Exception) {
            throw CoreException("Не удалось создать файл в папке Music: ${e.message ?: "ошибка хранилища"}.", e)
        } ?: throw CoreException("Не удалось создать файл в папке Music.")
        val pfd = try { cr.openFileDescriptor(uri, "rw") } catch (e: Exception) { null }
        if (pfd == null) { try { cr.delete(uri, null, null) } catch (_: Exception) {}; throw CoreException("Не удалось открыть новый файл для записи.") }
        return Pending(uri, actualName(uri) ?: name, pfd)
    }

    /** Завершить: закрыть дескриптор, IS_PENDING = 0, перечитать фактическое имя и размер. */
    fun commit(p: Pending): Saved {
        p.sink.flush()
        p.closeFd()
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        try { cr.update(p.uri, v, null, null) } catch (e: Exception) {
            abort(p); throw CoreException("Не удалось завершить сохранение файла.", e)
        }
        var name = p.displayName; var size = 0L
        try {
            cr.query(p.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; size = c.getLong(1) }
            }
        } catch (_: Exception) {}
        return Saved(p.uri, name, size)
    }

    /** Отменить: удалить недописанный файл. */
    fun abort(p: Pending) {
        p.closeFd()
        try { cr.delete(p.uri, null, null) } catch (_: Exception) {}
    }

    private fun actualName(uri: Uri): String? = try {
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

    companion object {
        const val RELATIVE_DIR = "Music/RAS audio editor/"
    }
}
