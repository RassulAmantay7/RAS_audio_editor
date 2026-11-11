package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.io.FileSource
import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit

/** Общие помощники тестов форматов: ресурсы, внешние инструменты (ffmpeg/ffprobe), мини-JSON. */
object T {
    fun res(path: String): File {
        val u = T::class.java.classLoader.getResource(path) ?: throw AssertionError("нет ресурса $path")
        return File(u.toURI())
    }
    fun fixture(name: String) = res("fixtures/$name")
    fun src(name: String) = FileSource(fixture(name))
    val vectors: Map<String, Any?> by lazy { Json.parse(res("ref/timeline_vectors.json").readText()) as Map<String, Any?> }

    private val cache = HashMap<String, Boolean>()
    fun has(tool: String): Boolean = cache.getOrPut(tool) {
        try { ProcessBuilder(tool, "-version").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor(20, TimeUnit.SECONDS) && it.exitValue() == 0 } }
        catch (e: Exception) { false }
    }
    /** Нет инструмента: пропуск, а при REQUIRE_TOOLS=1 — падение (docs/contracts.md). */
    fun need(vararg tools: String) {
        for (t in tools) if (!has(t)) {
            if (System.getenv("REQUIRE_TOOLS") == "1") throw AssertionError("нет инструмента $t (REQUIRE_TOOLS=1)")
            Assume.assumeTrue("нет $t", false)
        }
    }

    class Out(val stdout: ByteArray, val stderr: String)
    fun run(vararg cmd: String): Out {
        val err = File.createTempFile("err", ".txt")
        try {
            val p = ProcessBuilder(*cmd).redirectError(err).start()
            p.outputStream.close()
            val out = p.inputStream.readBytes()
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); throw AssertionError("таймаут: ${cmd.joinToString(" ")}") }
            val e = err.readText()
            if (p.exitValue() != 0) throw AssertionError("код ${p.exitValue()}: ${cmd.joinToString(" ")}\n$e")
            return Out(out, e)
        } finally { err.delete() }
    }
    fun ffmpeg(vararg a: String) = run("ffmpeg", "-hide_banner", "-loglevel", "error", "-threads", "1", "-y", *a)
    /** Декодировать в float32 LE (чередование). */
    fun decodeF32(f: File, extra: Array<String> = emptyArray()): FloatArray {
        val b = ffmpeg(*extra, "-i", f.path, "-f", "f32le", "-c:a", "pcm_f32le", "-").stdout
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat() }
    }
    fun probe(f: File, entries: String): Map<String, String> {
        val o = run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", entries, "-of", "default=nw=1", f.path)
        return String(o.stdout).lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }
    fun tmp(suffix: String): File = File.createTempFile("aefmt", suffix).also { it.deleteOnExit() }
}

/** Минимальный разбор JSON (для векторов эталона). Числа: Long, если целые, иначе Double. */
object Json {
    fun parse(s: String): Any? { val p = P(s); val v = p.value(); return v }
    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') break }; m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i++] == ']') break }; l }
                '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    val t = s.substring(st, i); if (t.any { it in ".eE" }) t.toDouble() else t.toLong() }
            }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) } }
                else sb.append(s[i])
                i++
            }
            i++; return sb.toString()
        }
    }
}

@Suppress("UNCHECKED_CAST") fun Any?.m(): Map<String, Any?> = this as Map<String, Any?>
@Suppress("UNCHECKED_CAST") fun Any?.l(): List<Any?> = this as List<Any?>
fun Any?.long(): Long = (this as Number).toLong()
fun Any?.int(): Int = (this as Number).toInt()
