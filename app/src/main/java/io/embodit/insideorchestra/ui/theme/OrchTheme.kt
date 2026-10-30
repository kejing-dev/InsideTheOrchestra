package io.embodit.insideorchestra.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * The app theme: SpatialUI Vibrant with all 16 colour roles inherited from the system (design
 * `palette.colorScheme`: nothing overridden) and the bundled Noto Sans type family. Brand colours
 * ([OrchColors]) only colour the domain content: bells, rack, plates, ripples, outlines, ghost.
 */
@Composable
fun OrchTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = systemColorScheme(context)
    val family = rememberOrchFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
