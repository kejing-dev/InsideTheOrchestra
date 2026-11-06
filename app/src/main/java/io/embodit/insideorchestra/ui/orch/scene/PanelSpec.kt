package io.embodit.insideorchestra.ui.orch.scene

/**
 * The one attachment panel left in the Stage (v0.1.5, D8 / D21: every other 2D control is in the system window):
 * the chip that says "打开面板" while the window is closed or minimised, or "平视前方 / 面板就出来" while an open waits
 * for a level head (native-window-move §3.3 / §7.9).
 */
object PanelSpec {
    const val CHIP = "windowChip"
    val CHIP_SIZE = 380f to 72f
    const val CHIP_SCALE = 0.9f

    val pool: List<String> = listOf(CHIP)
}
