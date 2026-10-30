package com.raceengineer.app

import kotlin.math.abs
import kotlin.math.roundToInt

/** Turns the raw words found on screen into meaningful values. Pure logic, no Android. */
object Parse {

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private const val LOOKALIKES = "OoDQIl|"

    /** Repairs letters that the reader often confuses with digits (O -> 0, l -> 1 ...). */
    fun digitsFix(t: String): String {
        val sb = StringBuilder()
        for (ch in t) {
            sb.append(
                when (ch) {
                    'O', 'o', 'D', 'Q' -> '0'
                    'I', 'l', '|' -> '1'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** Only repair look-alike letters inside tokens that already contain a digit. */
    private fun fix(t: String): String = if (t.any { it.isDigit() }) digitsFix(t) else t

    fun isNumLike(s: String): Boolean =
        s.isNotEmpty() && s.any { it.isDigit() } && s.all { it.isDigit() || LOOKALIKES.indexOf(it) >= 0 }

    fun toInt(s: String): Int? {
        val digits = digitsFix(s).filter { it.isDigit() }
        return if (digits.isEmpty()) null else digits.toIntOrNull()
    }

    /** Groups words into lines by height and joins them: "word word | word word". */
    fun joinLines(words: List<Word>, tol: Float): String {
        val sorted = words.sortedBy { it.cy }
        val lines = ArrayList<MutableList<Word>>()
        for (w in sorted) {
            val last = lines.lastOrNull()
            if (last != null && abs(last[0].cy - w.cy) < tol) last.add(w) else lines.add(mutableListOf(w))
        }
        return lines.joinToString(" | ") { l -> l.sortedBy { it.cx }.joinToString(" ") { it.text } }
    }

    // ------------------------------------------------------------------
    // Timing tower
    // ------------------------------------------------------------------

    private val gapRe = Regex("""[+]?\d{1,3}[.,]\d{3}""")
    private val lapGapRe = Regex("""LAP\s*(\d+)""")
    private val penSecRe = Regex("""(\d+)\s*S""")

    fun tower(words: List<Word>): List<TowerRow> {
        val byRow = HashMap<Int, MutableList<Word>>()
        for (w in words) {
            val idx = ((w.cy - Cfg.ROW_Y0) / Cfg.ROW_DY).roundToInt()
            if (idx < 0 || idx >= Cfg.MAX_ROWS) continue
            byRow.getOrPut(idx) { ArrayList() }.add(w)
        }
        val out = ArrayList<TowerRow>()
        for (idx in byRow.keys.sorted()) {
            val ws = byRow[idx]!!.sortedBy { it.cx }
            val main = ws.filter { it.cx < Cfg.PENALTY_X }
            val marker = ws.filter { it.cx >= Cfg.PENALTY_X }

            // position number: first number 1..30 in the left part of the row
            var posIdx = -1
            for ((i, w) in main.withIndex()) {
                val n = fix(w.text).toIntOrNull()
                if (n != null && n in 1..30 && w.cx < 0.115f) {
                    posIdx = i
                    break
                }
            }
            if (posIdx < 0) continue

            val row = TowerRow(idx)
            row.pos = fix(main[posIdx].text).toInt()
            val rest = main.drop(posIdx + 1)
            val codeIdx = rest.indexOfFirst { it.text.length == 3 && it.text.all { c -> c.isLetter() } }
            if (codeIdx >= 0) row.code = rest[codeIdx].text.uppercase()
            val gapTokens = rest.drop(codeIdx + 1).map { fix(it.text) }
            applyGap(row, gapTokens.joinToString(" "))

            val mk = marker.joinToString("") { it.text }.uppercase()
            if (mk.startsWith("P") || mk.contains("PIT")) row.inPit = true
            penSecRe.find(mk)?.let { row.penaltySeconds = it.groupValues[1].toIntOrNull() }
            out.add(row)
        }
        return out
    }

    private fun applyGap(row: TowerRow, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val up = t.uppercase()
        val m = gapRe.find(t)
        if (m != null) {
            val clean = m.value.replace(',', '.').removePrefix("+")
            row.gapSeconds = clean.toDoubleOrNull()
            row.gapText = "+$clean"
            return
        }
        if (up.contains("RET")) {
            row.retired = true
            row.gapText = "RET"
            return
        }
        val lm = lapGapRe.find(up)
        if (lm != null) {
            row.isLeaderRow = true
            row.gapText = "LAP " + lm.groupValues[1]
            return
        }
        if (up.contains("PIT")) {
            row.inPit = true
            val n = Regex("""\((\d+)\)""").find(up)?.groupValues?.get(1)
            row.gapText = if (n != null) "PIT ($n)" else "PIT"
            return
        }
        if (up == "GAP") {
            row.gapText = "GAP"
        }
    }

    // ------------------------------------------------------------------
    // Header (flags, lap counter)
    // ------------------------------------------------------------------

    fun header(words: List<Word>): HeaderInfo {
        val raw = joinLines(words, 0.012f)
        val up = raw.uppercase()

        var lap: String? = null
        val li = up.indexOf("LAP")
        if (li >= 0) {
            val after = digitsFix(up.substring(li + 3))
            val m = Regex("""(\d{1,2})\s*/""").find(after)
            if (m != null) {
                val total = Regex("""/\s*(\d{2,3})""").find(after)?.groupValues?.get(1)
                lap = m.groupValues[1] + "/" + (total ?: "?")
            }
        }

        var sector: String? = null
        val si = up.indexOf("SECTOR")
        if (si >= 0) {
            sector = Regex("[123]").find(digitsFix(up.substring(si + 6).take(4)))?.value
        }

        val status = when {
            up.contains("SAFETY") -> "SAFETY CAR"
            up.contains("VIRTUAL") || up.contains("VSC") -> "VIRTUAL SAFETY CAR"
            up.contains("YELLOW") -> if (sector != null) "YELLOW FLAG S$sector" else "YELLOW FLAG"
            up.contains("TRACK") || up.contains("CLEARED") -> "TRACK CLEARED"
            Regex("""\bRED\s+FLAG""").containsMatchIn(up) -> "RED FLAG"
            else -> "none"
        }
        return HeaderInfo(lap, status, raw)
    }

    // ------------------------------------------------------------------
    // Centre-top notice banner (penalty, fastest lap)
    // ------------------------------------------------------------------

    fun notice(words: List<Word>): NoticeInfo? {
        if (words.isEmpty()) return null
        val text = joinLines(words, 0.012f)
        val up = text.uppercase()
        if (up.contains("PENALTY")) {
            val sec = Regex("""(\d+)\s*SECONDS?\s*FOR\s*\(?([A-Z]{3})""").find(up)
            val why = Regex("""WITH\s*\(?([A-Z]{3})""").find(up)
            val sb = StringBuilder("time penalty")
            if (sec != null) sb.append(": ${sec.groupValues[1]}s for ${sec.groupValues[2]}")
            if (why != null) sb.append(", collision with ${why.groupValues[1]}")
            return NoticeInfo("PENALTY", sb.toString())
        }
        if (up.contains("FASTEST")) {
            val time = Regex("""\d{1,2}[:;.]\d{2}[.,]\d{3}""").find(text)
            val sb = StringBuilder("fastest lap")
            if (time != null) sb.append(" ${time.value}")
            return NoticeInfo("FASTEST LAP", sb.toString())
        }
        val wordsWithLetters = words.count { w -> w.text.count { c -> c.isLetter() } >= 3 }
        val letters = text.count { it.isLetter() }
        if (wordsWithLetters < 3 || letters < 14) return null
        return NoticeInfo("OTHER", text)
    }

    // ------------------------------------------------------------------
    // Steering wheel display
    // ------------------------------------------------------------------

    private fun numLeft(words: List<Word>, label: Word, tol: Float): Int? {
        val c = words.filter {
            it !== label && it.cx < label.cx && abs(it.cy - label.cy) < tol && isNumLike(it.text)
        }
        val best = c.minWithOrNull(compareBy<Word>({ abs(it.cy - label.cy) }, { -it.cx })) ?: return null
        return toInt(best.text)
    }

    private fun numAbove(words: List<Word>, label: Word, maxDy: Float): Int? {
        val c = words.filter {
            it !== label && it.cy < label.cy && label.cy - it.cy < maxDy &&
                abs(it.cx - label.cx) < 0.02f && isNumLike(it.text)
        }
        val best = c.maxByOrNull { it.cy } ?: return null
        return toInt(best.text)
    }

    /** [tol] = how far apart (in screen height) two words can be and still be on the same row. */
    fun wheel(words: List<Word>, tol: Float): LinkedHashMap<String, String> {
        val res = LinkedHashMap<String, String>()
        if (words.size < 4) return res

        words.firstOrNull { it.text.contains("KPH", true) }?.let { l ->
            numAbove(words, l, tol * 4)?.let { res["speed"] = it.toString() }
        }
        words.firstOrNull { it.text.contains("Temp", true) }?.let { l ->
            numLeft(words, l, tol)?.let { res["temp"] = it.toString() }
        }
        words.firstOrNull { it.text.contains("Tyre", true) }?.let { l ->
            numLeft(words, l, tol)?.let { res["tyres%"] = it.toString() }
        }
        words.firstOrNull { it.text.equals("Car", true) }?.let { l ->
            numLeft(words, l, tol)?.let { res["car%"] = it.toString() }
        }
        words.firstOrNull {
            val t = it.text.uppercase()
            t == "ER" || t == "ER%"
        }?.let { l ->
            numAbove(words, l, tol * 4)?.let { res["er%"] = it.toString() }
        }
        words.firstOrNull { it.text.startsWith("Pos", true) }?.let { l ->
            val n = words.filter { it.cx > l.cx && abs(it.cy - l.cy) < tol && isNumLike(it.text) }
                .minByOrNull { it.cx }
            if (n != null) toInt(n.text)?.let { res["pos"] = it.toString() }
        }
        words.firstOrNull { it.text.equals("Lap", true) }?.let { l ->
            val txt = words.filter { it.cx > l.cx && abs(it.cy - l.cy) < tol }
                .sortedBy { it.cx }.joinToString("") { it.text }
            Regex("""(\d+)\s*/\s*(\d+)""").find(digitsFix(txt))?.let {
                res["lap"] = it.groupValues[1] + "/" + it.groupValues[2]
            }
        }
        words.firstOrNull { it.text.contains("DELTA", true) }?.let { l ->
            val txt = words.filter { it.cx < l.cx && abs(it.cy - l.cy) < tol * 1.5f }
                .sortedBy { it.cx }.joinToString("") { it.text }
            Regex("""[+-]?\s*\d{1,3}[.,]\d{3}""").find(digitsFix(txt))?.let {
                res["delta"] = it.value.replace(" ", "")
            }
        }
        return res
    }

    // ------------------------------------------------------------------
    // Top-right timing panel
    // ------------------------------------------------------------------

    fun timing(words: List<Word>): LinkedHashMap<String, String> {
        val res = LinkedHashMap<String, String>()
        for (key in listOf("CURRENT", "BEST", "FASTEST", "DELTA")) {
            val lab = words.firstOrNull { it.text.uppercase().startsWith(key) } ?: continue
            val v = words.filter {
                it !== lab && it.cx > lab.cx && abs(it.cy - lab.cy) < 0.0075f &&
                    (it.text.any { c -> c.isDigit() } || it.text.contains('-'))
            }.maxByOrNull { it.cx } ?: continue
            res[key.lowercase()] = digitsFix(v.text)
        }
        return res
    }

    // ------------------------------------------------------------------
    // Top-right widget: speed, gear, tyre temperature
    // ------------------------------------------------------------------

    fun widget(words: List<Word>): LinkedHashMap<String, String> {
        val res = LinkedHashMap<String, String>()

        words.filter { isNumLike(it.text) && it.cx in 0.765f..0.805f && it.cy in 0.230f..0.254f }
            .maxByOrNull { it.text.length }?.let { w -> toInt(w.text)?.let { res["speed"] = it.toString() } }

        words.filter { it.text.length == 1 && it.cx in 0.845f..0.875f && it.cy in 0.262f..0.305f }
            .firstOrNull()?.let { w ->
                val ch = w.text.uppercase()
                if (ch[0].isDigit() || ch == "N" || ch == "R") res["gear"] = ch
            }

        words.filter { it.text.any { c -> c.isDigit() } && it.cx in 0.885f..0.92f && it.cy in 0.275f..0.305f }
            .firstOrNull()?.let { w ->
                var digits = digitsFix(w.text).filter { it.isDigit() }
                if (digits.length == 3 && (digits.toIntOrNull() ?: 0) > 150) digits = digits.take(2)
                val t = digits.toIntOrNull()
                if (t != null && t in 20..150) res["temp"] = digits
            }
        return res
    }
}
