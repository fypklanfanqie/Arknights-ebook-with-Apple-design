package com.lfq06.arknightsreader.turn

/** Result of resolving a drag direction from horizontal displacement. */
sealed interface DragDirection {
    /** Not enough displacement yet to pick a side. */
    data object Pending : DragDirection

    /** The drag has committed to [dir]. */
    data class Resolved(val dir: Int) : DragDirection

    /** The drag picked [dir], but that turn is unavailable. */
    data class Blocked(val dir: Int) : DragDirection
}
