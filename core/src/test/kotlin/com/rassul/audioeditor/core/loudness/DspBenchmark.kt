package com.rassul.audioeditor.core.loudness

import com.rassul.audioeditor.core.dsp.Resampler

/**
 * JVM-бенчмарк (не тест; T8): 30 мин стерео 48 кГц — LUFS + true peak; ресемплинг 30 мин стерео 44,1 → 48 кГц.
 * Сигнал генерируется кусками (без 691 МБ в памяти). Запуск: java -cp … com.rassul.audioeditor.core.loudness.DspBenchmark
 */
object DspBenchmark {
    private fun fill(buf: FloatArray, frames: Int, start: Long, fs: Int, rnd: java.util.Random) {
        for (i in 0 until frames) {
            val t = (start + i).toDouble() / fs
            val v = 0.3 * Math.sin(2 * Math.PI * 440 * t) * (0.5 + 0.5 * Math.sin(t)) + 0.05 * rnd.nextGaussian()
            buf[2 * i] = v.toFloat(); buf[2 * i + 1] = (0.8 * v).toFloat()
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val chunk = 65_536
        val buf = FloatArray(2 * chunk)
        // 1) LUFS + TP, 30 мин, 48 кГц, стерео (генерация сигнала — вне замера).
        run {
            val fs = 48_000; val total = 30L * 60 * fs
            val rnd = java.util.Random(1)
            val blocks = ArrayList<FloatArray>()
            for (k in 0 until 64) { val b = FloatArray(2 * chunk); fill(b, chunk, k.toLong() * chunk, fs, rnd); blocks.add(b) }
            val m = LoudnessMeter(fs, 2)
            val t0 = System.nanoTime()
            var done = 0L; var k = 0
            while (done < total) { val n = minOf(chunk.toLong(), total - done).toInt(); m.process(blocks[k++ % 64], n); done += n }
            val r = m.result()
            println("LUFS+TP 30 мин 48 кГц стерео: %.2f с (I=%.2f LUFS, TP=%.2f dBTP)".format((System.nanoTime() - t0) / 1e9, r.integratedLufs, r.truePeakDbtp))
        }
        // 2) Ресемплинг 44,1 → 48 кГц, 30 мин стерео.
        run {
            val fsIn = 44_100; val total = 30L * 60 * fsIn
            val rnd = java.util.Random(2)
            val blocks = ArrayList<FloatArray>()
            for (k in 0 until 64) { val b = FloatArray(2 * chunk); fill(b, chunk, k.toLong() * chunk, fsIn, rnd); blocks.add(b) }
            val rs = Resampler(fsIn, 48_000, 2)
            val out = FloatArray(2 * rs.maxOutput(chunk))
            val t0 = System.nanoTime()
            var done = 0L; var outN = 0L; var k = 0
            while (done < total) { val n = minOf(chunk.toLong(), total - done).toInt(); outN += rs.process(blocks[k++ % 64], n, out); done += n }
            outN += rs.flush(out)
            println("Ресемплинг 44,1→48 кГц 30 мин стерео: %.2f с (выход %d кадров, ожидалось %d)".format(
                (System.nanoTime() - t0) / 1e9, outN, Resampler.outputLength(total, fsIn, 48_000)))
        }
    }
}
