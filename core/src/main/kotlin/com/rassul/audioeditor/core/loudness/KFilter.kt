package com.rassul.audioeditor.core.loudness

/**
 * Коэффициенты K-фильтра (ITU-R BS.1770-5, Annex 1, табл. 1–2) для любой fs.
 * Метод (dsp.md табл. ф-ла 7, «FR-C4. Громкость» п. 1; эталон tools/ref/kfilter.py):
 * аналоговые прототипы (полка + RLB-ФВЧ) с параметрами B. De Man / libebur128 (ebur128.c, ebur128_init_filter),
 * билинейное преобразование с предыскажением K = tan(π·f0/fs).
 * Для 48 кГц совпадает с таблицей BS.1770 до 9·10⁻¹⁶.
 */
data class KFilterCoeffs(
    val s1b0: Double, val s1b1: Double, val s1b2: Double, val s1a1: Double, val s1a2: Double,
    /** Второй каскад: b = [1, −2, 1] без нормировки (как в табл. 2 BS.1770). */
    val s2a1: Double, val s2a2: Double,
)

object KFilter {
    const val SHELF_F0 = 1681.974450955533
    const val SHELF_G_DB = 3.999843853973347
    const val SHELF_Q = 0.7071752369554196
    const val SHELF_VB_EXP = 0.4996667741545416
    const val HP_F0 = 38.13547087602444
    const val HP_Q = 0.5003270373238773

    fun coefficients(fs: Int): KFilterCoeffs {
        require(fs > 0)
        // Каскад 1 (полка): K = tan(π f0/fs), Vh = 10^(G/20), Vb = Vh^0,49967;
        // a0' = 1 + K/Q + K²; b = [(Vh + Vb·K/Q + K²), 2(K² − Vh), (Vh − Vb·K/Q + K²)]/a0';
        // a = [1, 2(K² − 1)/a0', (1 − K/Q + K²)/a0']  (kfilter.py, De Man 2014).
        var k = Math.tan(Math.PI * SHELF_F0 / fs)
        val vh = Math.pow(10.0, SHELF_G_DB / 20.0)
        val vb = Math.pow(vh, SHELF_VB_EXP)
        val a0 = 1.0 + k / SHELF_Q + k * k
        val s1b0 = (vh + vb * k / SHELF_Q + k * k) / a0
        val s1b1 = 2.0 * (k * k - vh) / a0
        val s1b2 = (vh - vb * k / SHELF_Q + k * k) / a0
        val s1a1 = 2.0 * (k * k - 1.0) / a0
        val s1a2 = (1.0 - k / SHELF_Q + k * k) / a0
        // Каскад 2 (RLB ФВЧ): b = [1, −2, 1]; a = [1, 2(K² − 1)/d, (1 − K/Q + K²)/d], d = 1 + K/Q + K².
        k = Math.tan(Math.PI * HP_F0 / fs)
        val d = 1.0 + k / HP_Q + k * k
        val s2a1 = 2.0 * (k * k - 1.0) / d
        val s2a2 = (1.0 - k / HP_Q + k * k) / d
        return KFilterCoeffs(s1b0, s1b1, s1b2, s1a1, s1a2, s2a1, s2a2)
    }

    /** |H(f)| K-фильтра в дБ (для проверки: +0,691 дБ на 997 Гц при 48 кГц, dsp.md K-3). */
    fun responseDb(c: KFilterCoeffs, f: Double, fs: Int): Double {
        val w = -2.0 * Math.PI * f / fs
        val zr = Math.cos(w); val zi = Math.sin(w)          // z^-1
        val z2r = zr * zr - zi * zi; val z2i = 2 * zr * zi   // z^-2
        fun mag(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): Double {
            val nr = b0 + b1 * zr + b2 * z2r; val ni = b1 * zi + b2 * z2i
            val dr = 1.0 + a1 * zr + a2 * z2r; val di = a1 * zi + a2 * z2i
            return Math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        val h = mag(c.s1b0, c.s1b1, c.s1b2, c.s1a1, c.s1a2) * mag(1.0, -2.0, 1.0, c.s2a1, c.s2a2)
        return 20.0 * Math.log10(h)
    }
}

/**
 * Интерполятор true peak: ITU-R BS.1770-5 Annex 2, 48-отводный 4-фазный КИХ (все значения кратны 2⁻¹³).
 * Фаза 2 = фаза 1 в обратном порядке, фаза 3 = фаза 0 в обратном порядке (dsp.md «FR-C4» п. 6).
 * ×4 при fs < 96 кГц; ×2 при fs ≥ 96 кГц — фазы 0 и 2 (dsp.md П-6).
 */
object TruePeakFilter {
    val PHASE0 = doubleArrayOf(
        0.0017089843750, 0.0109863281250, -0.0196533203125, 0.0332031250000,
        -0.0594482421875, 0.1373291015625, 0.9721679687500, -0.1022949218750,
        0.0476074218750, -0.0266113281250, 0.0148925781250, -0.0083007812500,
    )
    val PHASE1 = doubleArrayOf(
        -0.0291748046875, 0.0292968750000, -0.0517578125000, 0.0891113281250,
        -0.1665039062500, 0.4650878906250, 0.7797851562500, -0.2003173828125,
        0.1015625000000, -0.0582275390625, 0.0330810546875, -0.0189208984375,
    )
    val PHASE2 = PHASE1.reversedArray()
    val PHASE3 = PHASE0.reversedArray()
    const val TAPS = 12

    fun phasesFor(fs: Int): Array<DoubleArray> =
        if (fs < 96_000) arrayOf(PHASE0, PHASE1, PHASE2, PHASE3) else arrayOf(PHASE0, PHASE2)
}
