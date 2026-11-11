package com.rassul.audioeditor.store

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.editor.SavedFile
import org.json.JSONArray
import org.json.JSONObject

/*
 * Зона MEDIA, T18. FR-28: список файлов, созданных приложением (READ_MEDIA_AUDIO не запрашиваем, поэтому
 * свой список в SharedPreferences + org.json). Отметка «не прошёл проверку» (FR-33, Д-16).
 */
class SavedRepository private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("saved_files", Context.MODE_PRIVATE)
    private val lock = Any()

    private fun read(): MutableList<SavedFile> = synchronized(lock) {
        val out = ArrayList<SavedFile>()
        val arr = try { JSONArray(sp.getString(K_ITEMS, "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += SavedFile(Uri.parse(o.optString("uri")), o.optString("name"), o.optLong("size"), o.optLong("dur"),
                o.optLong("date"), o.optBoolean("vf", false))
        }
        out
    }

    private fun write(list: List<SavedFile>) = synchronized(lock) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("uri", f.uri.toString()).put("name", f.name).put("size", f.sizeBytes)
            .put("dur", f.durationMs).put("date", f.dateMs).put("vf", f.verifyFailed))
        sp.edit().putString(K_ITEMS, arr.toString()).apply()
    }

    /** Все записи (новые сверху), без проверки существования. */
    fun all(): List<SavedFile> = read().sortedByDescending { it.dateMs }

    /** Имена — для FileNaming (FR-27): чтобы не наступать на свои же файлы. */
    fun names(): Set<String> = read().mapTo(HashSet()) { it.name }

    fun add(f: SavedFile) = synchronized(lock) { val l = read(); l.removeAll { it.uri == f.uri }; l += f; write(l) }

    fun setVerifyFailed(uri: Uri, failed: Boolean) = synchronized(lock) {
        val l = read().map { if (it.uri == uri) it.copy(verifyFailed = failed) else it }; write(l)
    }

    /** Существует ли файл (query по uri). Не из UI-потока при длинном списке. */
    fun exists(uri: Uri): Boolean = try {
        app.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (_: Exception) { false }

    /** Список с удалением записей о файлах, которых больше нет (удалены в «Файлах»). */
    fun listExisting(): List<SavedFile> {
        val l = read()
        val keep = l.filter { exists(it.uri) }
        if (keep.size != l.size) write(keep)
        return keep.sortedByDescending { it.dateMs }
    }

    /** Удалить свой файл (без доп. прав). Бросает CoreException с понятным текстом, если файл не наш (после переустановки). */
    fun delete(f: SavedFile) {
        try {
            app.contentResolver.delete(f.uri, null, null)
        } catch (e: RecoverableSecurityException) {
            throw CoreException("Этот файл создан до переустановки приложения — удалите его в приложении «Файлы».", e)
        } catch (e: SecurityException) {
            throw CoreException("Нет прав удалить этот файл — удалите его в приложении «Файлы».", e)
        }
        synchronized(lock) { val l = read(); l.removeAll { it.uri == f.uri }; write(l) }
    }

    /** FR-28: «Поделиться» (ACTION_SEND с content-URI и временным правом чтения). */
    fun shareIntent(f: SavedFile): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(app.contentResolver.getType(f.uri) ?: mimeFromName(f.name))
            .putExtra(Intent.EXTRA_STREAM, f.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Поделиться: ${f.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        private const val K_ITEMS = "items"
        @Volatile private var inst: SavedRepository? = null
        fun get(ctx: Context): SavedRepository = inst ?: synchronized(this) { inst ?: SavedRepository(ctx).also { inst = it } }

        fun mimeFromName(name: String): String = com.rassul.audioeditor.media.RemuxCopier.mimeForExt(name.substringAfterLast('.', "").lowercase())
    }
}
