package com.raceengineer.app

/**
 * Where things are on the Monoposto screen. Everything is a fraction of the screen,
 * measured from real screenshots (2304 x 1440). If a game update moves the HUD,
 * these are the only numbers that need changing.
 */
object Cfg {
    // How often we read the screen (milliseconds). Higher = cooler tablet, slower updates.
    const val INTERVAL_MS = 500L

    // ---- Timing tower ----
    const val ROW_Y0 = 0.1015f      // centre of row 1
    const val ROW_DY = 0.02327f     // distance between rows
    const val MAX_ROWS = 24
    const val PENALTY_X = 0.21f     // anything right of this is a marker (! 5s / P)

    // Tyre ring (coloured circle with the tyre age inside)
    const val RING_CX = 0.078f
    const val RING_R = 0.0060f

    val HEADER = Region(0.085f, 0.025f, 0.225f, 0.088f)
    val TOWER = Region(0.088f, 0.090f, 0.245f, 0.655f)
    val RING_STRIP_L = 0.0695f
    val RING_STRIP_R = 0.0865f

    // ---- Other HUD parts ----
    val NOTICE = Region(0.405f, 0.014f, 0.595f, 0.105f)
    val TIMING = Region(0.76f, 0.11f, 0.96f, 0.225f)
    val WIDGET = Region(0.76f, 0.225f, 0.96f, 0.35f)

    // Steering wheel display: big in the POV camera, small in the standard camera
    val WHEEL_POV = Region(0.42f, 0.61f, 0.59f, 0.77f)
    val WHEEL_STD = Region(0.52f, 0.70f, 0.60f, 0.78f)

    // Where the on-screen box sits: bottom-left, clear of every HUD part we read.
    const val OVERLAY_BOTTOM_FRACTION = 0.17f
}
