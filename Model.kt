package com.raceengineer.app

/** A rectangle on the screen, as fractions (0.0 - 1.0) of the screen width and height. */
class Region(val l: Float, val t: Float, val r: Float, val b: Float)

/** One word found on screen. cx / cy are its centre as fractions of the whole screen. */
class Word(val text: String, val cx: Float, val cy: Float)

/** Access to the pixels of one captured frame. Colours are ARGB ints. */
interface PixelGrid {
    val width: Int
    val height: Int
    fun pixel(x: Int, y: Int): Int
}

/** Reads the text inside part of the screen. [scale] = how much to enlarge that part first. */
fun interface TextReader {
    fun read(region: Region, scale: Float): List<Word>
}

/** One line of the timing tower. */
class TowerRow(val index: Int) {
    var pos: Int? = null
    var code: String? = null
    var gapText: String? = null
    var gapSeconds: Double? = null
    var tyre: String = "?"
    var age: Int? = null
    var penaltySeconds: Int? = null
    var penaltyMarker: Boolean = false
    var inPit: Boolean = false
    var retired: Boolean = false
    var isMe: Boolean = false
    var isLeaderRow: Boolean = false
}

class HeaderInfo(val lap: String?, val status: String, val raw: String)

class NoticeInfo(val kind: String, val summary: String)
