package com.raceengineer.app

/** Small notice board the service writes to and the main screen reads from. */
object Shared {
    /** The last reading that really looked like the game (kept even when you leave the game). */
    @Volatile
    var full: String = "Nothing read yet. Tap START, then switch to Monoposto."

    @Volatile
    var running: Boolean = false

    /** True while the Race Engineer screen is in front: the service then does not read. */
    @Volatile
    var paused: Boolean = false

    /** Short messages such as "Snapshot saved". */
    @Volatile
    var note: String = ""
}
