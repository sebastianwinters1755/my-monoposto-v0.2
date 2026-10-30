package com.raceengineer.app

/** Everything the app understood from ONE captured frame. */
class Snapshot {
    var header: HeaderInfo = HeaderInfo(null, "none", "")
    var notice: NoticeInfo? = null
    var rows: List<TowerRow> = emptyList()
    var wheel: Map<String, String> = emptyMap()
    var wheelCamera: String = "-"
    var widget: Map<String, String> = emptyMap()
    var tyreIcon: String = "?"
    var timing: Map<String, String> = emptyMap()
    var rawWheel: String = ""
    var rawWidget: String = ""
    var rawTiming: String = ""
    var rawTower: String = ""
    var rawNotice: String = ""

    val mode: String
        get() = when {
            rows.size >= 6 -> "OPEN"
            rows.isNotEmpty() -> "SHRUNK"
            else -> "NONE"
        }

    val me: TowerRow?
        get() = rows.firstOrNull { it.isMe }

    /** Short text for the on-screen box. */
    fun compact(): String {
        val sb = StringBuilder()
        sb.append("Lap ${header.lap ?: "?"}  ${header.status}\n")
        val n = notice
        if (n != null) sb.append("${n.summary.take(34)}\n")

        if (rows.isEmpty()) {
            sb.append("tower: not visible\n")
        } else {
            val m = me
            sb.append("tower $mode ${rows.size} rows")
            sb.append(if (m != null) "  you P${m.pos ?: "?"}\n" else "  you ?\n")
            if (m != null) {
                if (mode == "OPEN") {
                    sb.append("to leader ${m.gapText ?: "-"}\n")
                } else {
                    val sorted = rows.sortedBy { it.index }
                    val i = sorted.indexOf(m)
                    if (i > 0) sb.append("ahead ${sorted[i - 1].code ?: "?"}  ${m.gapText ?: "-"}\n")
                    if (i >= 0 && i < sorted.size - 1) {
                        val b = sorted[i + 1]
                        sb.append("behind ${b.code ?: "?"}  ${b.gapText ?: "-"}\n")
                    }
                }
                val flags = ArrayList<String>()
                if (m.inPit) flags.add("PIT")
                if (m.penaltySeconds != null) flags.add("PEN ${m.penaltySeconds}s")
                else if (m.penaltyMarker) flags.add("PEN ?")
                sb.append("tyre ${m.tyre}${m.age ?: "?"}")
                if (flags.isNotEmpty()) sb.append("  ${flags.joinToString(" ")}")
                sb.append("\n")
            }
        }
        val w = wheel
        if (w.isNotEmpty()) {
            sb.append("wear ${w["tyres%"] ?: "?"}%  car ${w["car%"] ?: "?"}%  T ${w["temp"] ?: widget["temp"] ?: "?"}\n")
        } else {
            sb.append("wear ?  T ${widget["temp"] ?: "?"} ($tyreIcon)\n")
        }
        return sb.toString().trimEnd()
    }

    /** Full report for the app screen and the saved text file. */
    fun full(): String {
        val sb = StringBuilder()
        sb.append("RACE    lap ${header.lap ?: "?"}   flag: ${header.status}\n")
        sb.append("NOTICE  ${notice?.summary ?: "none"}\n")
        val withGap = rows.count { it.gapText != null }
        val m = me
        sb.append("TOWER   $mode, ${rows.size} rows read, $withGap with a gap")
        sb.append(if (m != null) ", you = P${m.pos ?: "?"} ${m.code ?: ""}\n" else ", you = not found\n")
        sb.append("WHEEL   camera=$wheelCamera  ")
        sb.append(if (wheel.isEmpty()) "not read" else wheel.entries.joinToString("  ") { "${it.key}=${it.value}" })
        sb.append("\n")
        sb.append("WIDGET  ")
        sb.append(if (widget.isEmpty()) "not read" else widget.entries.joinToString("  ") { "${it.key}=${it.value}" })
        sb.append("  tyre icon=$tyreIcon\n")
        sb.append("TIMES   ")
        sb.append(if (timing.isEmpty()) "not read" else timing.entries.joinToString("  ") { "${it.key}=${it.value}" })
        sb.append("\n\n")
        for (r in rows.sortedBy { it.index }) {
            val flags = ArrayList<String>()
            if (r.isMe) flags.add("YOU")
            if (r.inPit) flags.add("PIT")
            if (r.penaltySeconds != null) flags.add("PEN ${r.penaltySeconds}s")
            else if (r.penaltyMarker) flags.add("PEN ?")
            if (r.retired) flags.add("RET")
            sb.append(
                "P%-2s %-4s gap %-10s tyre %s age %-3s %s\n".format(
                    r.pos?.toString() ?: "?", r.code ?: "???", r.gapText ?: "-",
                    r.tyre, r.age?.toString() ?: "?", flags.joinToString(" ")
                )
            )
        }
        sb.append("\n--- raw text the reader saw (for checking) ---\n")
        sb.append("header: ${header.raw}\n")
        sb.append("notice: $rawNotice\n")
        sb.append("wheel: $rawWheel\n")
        sb.append("widget: $rawWidget\n")
        sb.append("timing: $rawTiming\n")
        return sb.toString()
    }
}

/**
 * In the OPEN tower, gaps to the leader can only grow going down the order. A gap that is
 * smaller than the one above it was misread (for example a dropped digit), so we mark it
 * as unreliable instead of showing a wrong number.
 */
fun markSuspectGaps(rows: List<TowerRow>) {
    if (rows.size < 6) return
    val withGap = rows.sortedBy { it.index }.filter { it.gapSeconds != null }
    val n = withGap.size
    if (n < 3) return
    // Longest run of gaps that only grows going down the order = the trustworthy ones.
    val len = IntArray(n) { 1 }
    val prev = IntArray(n) { -1 }
    for (i in 0 until n) {
        for (j in 0 until i) {
            if (withGap[j].gapSeconds!! <= withGap[i].gapSeconds!! + 0.0005 && len[j] + 1 > len[i]) {
                len[i] = len[j] + 1
                prev[i] = j
            }
        }
    }
    var best = 0
    for (i in 0 until n) if (len[i] > len[best]) best = i
    val keep = HashSet<Int>()
    var k = best
    while (k >= 0) {
        keep.add(k)
        k = prev[k]
    }
    for (i in 0 until n) {
        if (!keep.contains(i)) {
            val r = withGap[i]
            r.gapText = (r.gapText ?: "") + "?"
            r.gapSeconds = null
        }
    }
}

/**
 * Looks at one frame of the game and works out what the HUD says.
 * v0.2: it only READS and reports. No voice or advice yet.
 */
class RaceReader(private val ocr: TextReader) {

    fun analyze(grid: PixelGrid): Snapshot {
        val s = Snapshot()

        // ---- Header: flags and lap counter ----
        s.header = Parse.header(ocr.read(Cfg.HEADER, 2f))

        // ---- Centre-top banner ----
        val noticeWords = ocr.read(Cfg.NOTICE, 1.5f)
        s.rawNotice = Parse.joinLines(noticeWords, 0.012f)
        s.notice = Parse.notice(noticeWords)

        // ---- Timing tower ----
        val towerWords = ocr.read(Cfg.TOWER, 1f)
        var rows: List<TowerRow> = Parse.tower(towerWords)
        if (rows.size >= 2) {
            // A row the text reader missed, sitting between rows it did read, can still be
            // recognised as MY row by its green cell.
            val have = HashSet<Int>()
            for (r in rows) have.add(r.index)
            val lo = rows.minOf { it.index }
            val hi = rows.maxOf { it.index }
            val extra = ArrayList<TowerRow>()
            for (idx in lo..hi) {
                if (have.contains(idx)) continue
                if (Colours.isMeRow(grid, idx)) {
                    val r = TowerRow(idx)
                    val above = rows.firstOrNull { it.index == idx - 1 }?.pos
                    val below = rows.firstOrNull { it.index == idx + 1 }?.pos
                    r.pos = if (above != null) above + 1 else if (below != null) below - 1 else null
                    extra.add(r)
                }
            }
            rows = rows + extra
        }
        if (rows.isNotEmpty()) {
            val lastIdx = rows.maxOf { it.index }
            val bottom = Cfg.ROW_Y0 + lastIdx * Cfg.ROW_DY + 0.014f
            val strip = Region(Cfg.RING_STRIP_L, Cfg.TOWER.t, Cfg.RING_STRIP_R, bottom)
            for (w in ocr.read(strip, 3f)) {
                if (!Parse.isNumLike(w.text)) continue
                val idx = Math.round((w.cy - Cfg.ROW_Y0) / Cfg.ROW_DY)
                val age = Parse.toInt(w.text) ?: continue
                if (age < 0 || age > 99) continue
                for (row in rows) {
                    if (row.index == idx) row.age = age
                }
            }
            var meFound = false
            for (row in rows) {
                row.tyre = Colours.ringColour(grid, row.index)
                row.penaltyMarker = Colours.hasPenaltyMarker(grid, row.index)
                if (!meFound && Colours.isMeRow(grid, row.index)) {
                    row.isMe = true
                    meFound = true
                }
            }
        }
        markSuspectGaps(rows)
        s.rows = rows

        // ---- Steering wheel display (big in POV camera, small in standard camera) ----
        val povWords = ocr.read(Cfg.WHEEL_POV, 2f)
        val pov = Parse.wheel(povWords, 0.009f)
        s.rawWheel = Parse.joinLines(povWords, 0.008f)
        if (pov.size >= 4) {
            s.wheel = pov
            s.wheelCamera = "POV"
        } else {
            val stdWords = ocr.read(Cfg.WHEEL_STD, 4f)
            val std = Parse.wheel(stdWords, 0.0045f)
            s.rawWheel = Parse.joinLines(stdWords, 0.004f)
            if (std.size >= pov.size) {
                s.wheel = std
                s.wheelCamera = "standard"
            } else {
                s.wheel = pov
                s.wheelCamera = "POV"
            }
        }

        // ---- Top-right widget: speed, gear, tyre temperature ----
        val widgetWords = ocr.read(Cfg.WIDGET, 2f)
        s.rawWidget = Parse.joinLines(widgetWords, 0.01f)
        s.widget = Parse.widget(widgetWords)
        s.tyreIcon = Colours.tyreTempState(grid)

        // ---- Top-right timing panel ----
        val timingWords = ocr.read(Cfg.TIMING, 1.5f)
        s.rawTiming = Parse.joinLines(timingWords, 0.007f)
        s.timing = Parse.timing(timingWords)
        return s
    }
}
