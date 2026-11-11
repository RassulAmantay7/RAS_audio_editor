package com.rassul.audioeditor.core.dsp

/**
 * Чтение эталонных векторов physics-math-reviewer из core/src/test/resources/ref/ (json, csv).
 * Мини-парсер JSON (объекты → Map, массивы → List, числа → Long или Double, NaN/±Infinity как в Python json).
 */
object RefData {
    fun text(name: String): String {
        val s = RefData::class.java.classLoader.getResourceAsStream("ref/$name")
            ?: java.io.File("core/src/test/resources/ref/$name").takeIf { it.exists() }?.inputStream()
            ?: error("нет ресурса ref/$name")
        return s.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    fun json(name: String): Any? = Json(text(name)).parse()

    /** CSV с заголовком → список строк-словарей. */
    fun csv(name: String): List<Map<String, String>> {
        val lines = text(name).lines().filter { it.isNotBlank() }
        val head = lines[0].split(',').map { it.trim() }
        return lines.drop(1).map { l -> head.zip(l.split(',').map { it.trim() }).toMap() }
    }

    private class Json(val s: String) {
        var i = 0
        fun parse(): Any? { val v = value(); ws(); require(i == s.length) { "мусор в конце JSON @$i" }; return v }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                'N' -> { expect("NaN"); Double.NaN }
                'I' -> { expect("Infinity"); Double.POSITIVE_INFINITY }
                else -> if (c == '-' && s.startsWith("-Infinity", i)) { i += 9; Double.NEGATIVE_INFINITY } else num()
            }
        }
        fun expect(w: String) { require(s.startsWith(w, i)) { "ожидалось $w @$i" }; i += w.length }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':'); i++
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}') { "ожидалось } @$i" }; i++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']') { "ожидалось ] @$i" }; i++; return l
            }
        }
        fun str(): String {
            require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }
}

// Удобные преобразования для тестов.
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Any?.list(): List<Any?> = this as List<Any?>
fun Any?.d(): Double = when (this) { is Number -> this.toDouble(); null -> Double.NaN; else -> error("не число: $this") }
fun Any?.l(): Long = (this as Number).toLong()
fun Any?.doubles(): DoubleArray = list().map { it.d() }.toDoubleArray()
