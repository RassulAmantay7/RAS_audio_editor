package com.rassul.audioeditor.core.audit.dsp

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Запуск Python-эталонов (скрипты tools/ref, pyloudnorm) из тестов аудита (code-formula-auditor, T13).
 * Нет python3/numpy/scipy/pyloudnorm или каталога tools/ref: пропуск (Assume), при REQUIRE_TOOLS=1 — падение
 * (docs/contracts.md, «Тесты, которым нужен ffmpeg/…»).
 */
object AuditPy {
    /** Каталог эталонных скриптов (тесты запускаются из корня репозитория, scripts/check.sh). */
    val refDir: File? = listOf(File("tools/ref"), File("../tools/ref")).firstOrNull { File(it, "loudness_ref.py").isFile }

    private fun unavailable(why: String): Nothing {
        if (System.getenv("REQUIRE_TOOLS") == "1") throw AssertionError("REQUIRE_TOOLS=1, но недоступно: $why")
        assumeTrue("пропуск: $why", false)
        throw IllegalStateException("unreachable")
    }

    /** Записать массив double как little-endian float64. */
    fun writeF64(f: File, x: DoubleArray) {
        val bb = ByteBuffer.allocate(x.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) bb.putDouble(v)
        f.writeBytes(bb.array())
    }

    fun writeF32(f: File, x: FloatArray) {
        val bb = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) bb.putFloat(v)
        f.writeBytes(bb.array())
    }

    fun readF64(f: File): DoubleArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(bb.remaining() / 8) { bb.double }
    }

    /**
     * Выполнить python-код (python3 -I; sys.path дополняется tools/ref). [body] получает каталог для обмена
     * файлами; возвращается stdout. Ошибка python — падение теста с текстом stderr.
     */
    fun run(code: String, prepare: (File) -> Unit = {}, after: (File, String) -> Unit = { _, _ -> }): String {
        val dir = refDir ?: unavailable("каталог tools/ref")
        val tmp = java.nio.file.Files.createTempDirectory("audit-py").toFile()
        try {
            prepare(tmp)
            val full = "import sys\nsys.stdout.reconfigure(encoding='utf-8')\nsys.path.insert(0, ${pyStr(dir.absolutePath)})\nW = ${pyStr(tmp.absolutePath)}\n" + code
            val p = try {
                ProcessBuilder("python3", "-I", "-c", full).redirectErrorStream(true).start()
            } catch (e: java.io.IOException) {
                unavailable("python3 (${e.message})")
            }
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val rc = p.waitFor()
            if (rc != 0) {
                if (out.contains("ModuleNotFoundError") || out.contains("ImportError")) unavailable("модуль python: ${out.lines().last { it.isNotBlank() }}")
                throw AssertionError("python завершился с кодом $rc:\n$out")
            }
            after(tmp, out)
            return out
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun pyStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** Последняя непустая строка stdout, разобранная как список чисел через пробел. */
    fun numbers(out: String): DoubleArray =
        out.lines().last { it.isNotBlank() }.trim().split(Regex("\\s+")).map {
            when (it) { "inf" -> Double.POSITIVE_INFINITY; "-inf" -> Double.NEGATIVE_INFINITY; "nan", "None" -> Double.NaN; else -> it.toDouble() }
        }.toDoubleArray()
}
