package io.embodit.insideorchestra.ui.theme

import androidx.compose.ui.graphics.Color
import io.embodit.insideorchestra.domain.model.Family

/**
 * The design's brand tokens (design package `palette.brandTokens`). This is the only file with colour
 * literals. They colour domain content only (rings, floor zones, name tags, card dots, the ghost); every
 * panel colour comes from the inherited SpatialUI scheme.
 */
object OrchColors {
    val Strings = Color(0xFF9C7BD6) // design-style: fixed-figma-color strings: wisteria
    val Woodwinds = Color(0xFF5FB98A) // design-style: fixed-figma-color woodwinds: bamboo
    val Brass = Color(0xFFE89A3C) // design-style: fixed-figma-color brass: brass gold
    val Percussion = Color(0xFF5C8FE6) // design-style: fixed-figma-color percussion: drumhead blue
    val ConcertBlack = Color(0xFF26262B) // design-style: fixed-figma-color concertBlack: name tag ground
    val Podium = Color(0xFF3A3A40) // design-style: fixed-figma-color podium
    val Tape = Color(0xFFB79CE6) // design-style: fixed-figma-color masking: footprint tape (strings, lighter)
    val Danger = Color(0xFFE5484D) // design-style: fixed-figma-color danger: ghost too close
    val TagInk = Color(0xFFF4F1EA) // design-style: fixed-figma-color tagInk: name tag text
    val Grid = Color(0xFFDDE3EA) // design-style: fixed-figma-color grid: virtual room lines
    val InkOnFill = Color(0xFF16181C) // design-style: fixed-figma-color ink: text on the interaction fill

    fun of(f: Family): Color =
        when (f) {
            Family.STR -> Strings
            Family.WW -> Woodwinds
            Family.BR -> Brass
            Family.PERC -> Percussion
        }
}
