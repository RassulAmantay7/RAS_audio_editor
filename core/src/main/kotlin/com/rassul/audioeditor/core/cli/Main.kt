package com.rassul.audioeditor.core.cli

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.EditOp
import com.rassul.audioeditor.core.edit.EditRules
import com.rassul.audioeditor.core.edit.Range
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.format.WavReader
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.PcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPipeline
import com.rassul.audioeditor.core.pipeline.ExportPlanner
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.time.TimeConv
import com.rassul.audioeditor.core.time.TimeParse
import com.rassul.audioeditor.core.verify.FlacDecoder
import com.rassul.audioeditor.core.verify.VerifyExpect
import com.rassul.audioeditor.core.verify.Verifier
import java.io.File
import java.io.PrintStream

/*
 * CLI ядра (T12): WAV/FLAC на входе → правки из аргументов (по порядку) → экспорт WAV/FLAC → автопроверка FR-33.
 * Тот же Renderer и ExportPipeline, что в приложении. Запуск: scripts/cli.sh.
 */

const val USAGE = """Использование: cli.sh ВХОД.(wav|flac) [правки...] [параметры] --out ВЫХОД.(wav|flac)
Правки (применяются по порядку; время — [[ч:]мм:]сс[.ммм] в координатах результата предыдущих правок):
  --keep НАЧАЛО КОНЕЦ        оставить фрагмент
  --delete НАЧАЛО КОНЕЦ      удалить фрагмент
  --gain ДБ [НАЧАЛО КОНЕЦ]   громкость (−40…+20 дБ)
  --fade-in СЕК | --fade-out СЕК   fade от начала / к концу файла
  --fade-curve linear|power|log    кривая следующих fade (по умолчанию power)
  --norm LUFS                нормализация «На слух» (true peak ≤ −1 dBTP)
  --norm-peak DBFS           нормализация по пику
Параметры:
  --no-smooth                не сглаживать стыки; --joint-ms МС — длительность сглаживания (5)
  --rate ГЦ                  частота результата; --bits 16|24|32 — разрядность (32 — WAV float)
  --seed N                   сид дизеринга (для повторяемости)"""

/** Вход: WAV (своим разборщиком) или FLAC (своим декодером, целиком в память). Возвращает источник и разрядность. */
fun openInput(f: File): Pair<PcmSource, Int> {
    val fs = FileSource(f)
    val head = ByteArray(4).also { fs.read(0, it, 0, 4) }
    if (String(head, Charsets.ISO_8859_1) == "fLaC") {
        val d = FlacDecoder(fs)
        val si = d.streamInfo
        val data = d.decodeToFloat()
        fs.close()
        return ArrayPcmSource(PcmSpec(si.sampleRate, si.channels), data) to si.bitsPerSample
    }
    val w = WavReader(fs)
    val src = object : PcmSource {
        override val spec = w.spec
        override val length = w.lengthFrames
        override fun read(from: Long, dst: FloatArray, frames: Int): Int =
            if (from >= length || frames <= 0) 0 else w.read(from, dst, minOf(frames.toLong(), length - from).toInt())
    }
    return src to (if (w.isFloat) 32 else w.bitsPerSample)
}

/** Выполнить CLI; возвращает код выхода (0 — успех, 1 — ошибка проверки/экспорта, 2 — ошибка аргументов). */
fun runCli(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
    if (args.isEmpty() || args[0] == "-h" || args[0] == "--help") { out.println(USAGE); return if (args.isEmpty()) 2 else 0 }
    try {
        val input = File(args[0])
        val (source, srcBits) = openInput(input)
        val fs = source.spec.sampleRate
        var settings = RenderSettings()
        var outFile: File? = null
        var rate: Int? = null; var bits: Int? = null; var seed: Long = System.nanoTime()
        var curve = FadeCurve.EQUAL_POWER
        // Правки откладываются как «замыкания» над текущей длиной результата: нужна длина и разрешение нормализации.
        val steps = ArrayList<(Long) -> EditOp>()
        var i = 1
        fun next(name: String): String = args.getOrNull(i++) ?: throw IllegalArgumentException("после $name нужно значение")
        fun time(s: String): Long = when (val r = TimeConv.parse(s, fs)) {
            is TimeParse.Ok -> r.samples
            is TimeParse.Error -> throw IllegalArgumentException("«$s»: ${r.message}")
        }
        fun num(s: String): Double = s.replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("«$s» — не число")
        while (i < args.size) {
            when (val a = args[i++]) {
                "--keep" -> { val r = Range(time(next(a)), time(next(a))); steps += { EditOp.Keep(r) } }
                "--delete" -> { val r = Range(time(next(a)), time(next(a))); steps += { EditOp.Delete(r) } }
                "--gain" -> {
                    val db = num(next(a))
                    require(db in -40.0..20.0) { "громкость от −40,0 до +20,0 дБ" }
                    val r = if (i + 1 < args.size && !args[i].startsWith("--")) Range(time(next(a)), time(next(a))) else null
                    steps += { EditOp.Volume(r, db) }
                }
                "--fade-in", "--fade-out" -> {
                    val ms = num(next(a)) * 1000.0
                    val fin = a == "--fade-in"; val c = curve
                    steps += { len -> EditOp.Fade(EditRules.fadeRangeWithoutSelection(ms, fs, len, fin), fin, c) }
                }
                "--fade-curve" -> curve = when (next(a)) {
                    "linear" -> FadeCurve.LINEAR; "power" -> FadeCurve.EQUAL_POWER; "log" -> FadeCurve.LOGARITHMIC
                    else -> throw IllegalArgumentException("кривая: linear, power или log")
                }
                "--norm" -> { val t = num(next(a)); steps += { EditOp.Normalize(NormalizeTarget.Lufs(t)) } }
                "--norm-peak" -> { val t = num(next(a)); steps += { EditOp.Normalize(NormalizeTarget.Peak(t)) } }
                "--no-smooth" -> settings = settings.copy(smoothJoints = false)
                "--joint-ms" -> settings = settings.copy(jointMs = num(next(a)).also { require(it in 1.0..50.0) { "сглаживание 1–50 мс" } })
                "--rate" -> rate = next(a).toInt()
                "--bits" -> bits = next(a).toInt()
                "--seed" -> seed = next(a).toLong()
                "--out" -> outFile = File(next(a))
                else -> throw IllegalArgumentException("неизвестный параметр «$a»")
            }
        }
        val dst = outFile ?: throw IllegalArgumentException("не задан --out")
        val format = when (dst.extension.lowercase()) {
            "wav" -> ExportFormat.WAV; "flac" -> ExportFormat.FLAC
            else -> throw IllegalArgumentException("выход: .wav или .flac")
        }
        // Список правок: каждая проверяется на длине результата предыдущих (EditRules), нормализация разрешается сразу.
        var list = EditList()
        for (s in steps) {
            val r = Renderer(source, list, settings)
            var op = s(r.length)
            EditRules.check(op, r.length)?.let { throw IllegalArgumentException("правка ${list.ops.size + 1}: $it") }
            if (op is EditOp.Normalize) op = r.resolveNormalize(op)
            list = list.plus(op)
        }
        val renderer = Renderer(source, list, settings)
        renderer.notices.forEach { out.println(it) }
        val plan = ExportPlanner.plan(source.spec, srcBits, ExportRequest(format, rate, bits))
        plan.notes.forEach { out.println(it) }
        val pipe = ExportPipeline(renderer, plan, seed)
        val res = FileSink(dst).use { pipe.writeTo(it) }
        if (res.clippedSamples > 0) out.println("Срезано сэмплов (|x| > 1,0): ${res.clippedSamples}")
        res.ditherSeed?.let { out.println("Сид дизеринга: $it") }
        out.println("Записано: ${dst.path}, ${res.frames} кадров (${TimeConv.format(res.frames, plan.outSpec.sampleRate)})")
        val rep = FileSource(dst).use { Verifier.verify(it, VerifyExpect.of(plan, res), flacFullDecode = true) }
        out.println(rep.message)
        rep.warnings.forEach { out.println(it) }
        return if (rep.ok) 0 else 1
    } catch (e: IllegalArgumentException) {
        err.println("Ошибка: ${e.message}"); return 2
    } catch (e: CoreException) {
        err.println("Ошибка: ${e.message}"); return 1
    }
}

fun main(args: Array<String>) {
    // Тексты — на русском: вывод всегда в UTF-8, независимо от кодировки консоли JVM.
    val o = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
    val e = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8")
    val code = runCli(args, o, e)
    if (code != 0) kotlin.system.exitProcess(code)
}
