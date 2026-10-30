package io.embodit.insideorchestra.ui.orch.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    /**
     * v0.1.1: the side panel with the shared controls once the orchestra is placed (title, mute, more; piece; transport;
     * hint + Back to place), 440 x 220. The section controls moved to one bar above each section ([bar]).
     */
    const val CONTROL = "controlPanel"

    /** The control panel while starting / placing: title, hint, Turn 45 (420 x 152). */
    const val PLACING = "placingPanel"
    const val MENU = "menuPanel"
    const val CARD = "cardPanel"
    const val SNACK = "snackPanel"

    /** D21: head-locked pill shown only while the world-fixed side panel is out of view: "Controls behind you · [Call back]". */
    const val RECALL = "recallPanel"
    val RECALL_SIZE = 360f to 64f

    val CONTROL_SIZE = 440f to 220f
    val PLACING_SIZE = 440f to 184f

    /** v0.1.1: one bar per section above its players: dot + name, BeatMap dots, Solo / Mute / Info (440 x 72). */
    val BAR_SIZE = 440f to 72f

    fun bar(f: io.embodit.insideorchestra.domain.model.Family): String = "bar" + f.id

    val BARS: List<String> = io.embodit.insideorchestra.domain.model.Family.entries.map { bar(it) }
    val MENU_SIZE = 300f to 476f
    val CARD_SIZE = 360f to 420f
    val SNACK_SIZE = 600f to 72f

    /** Side panel at 0.8 (1 dp = 0.64 mm): 0.28 m wide at 0.7 m. */
    const val MAIN_SCALE = 0.8f
    const val MENU_SCALE = 0.8f
    const val CARD_SCALE = 1.0f
    const val SNACK_SCALE = 0.9f
    const val RECALL_SCALE = 0.9f

    val pool: List<String> = listOf(CONTROL, PLACING, MENU, CARD, SNACK, RECALL) + BARS

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            CONTROL -> CONTROL_SIZE
            PLACING -> PLACING_SIZE
            MENU -> MENU_SIZE
            CARD -> CARD_SIZE
            SNACK -> SNACK_SIZE
            RECALL -> RECALL_SIZE
            else -> BAR_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
