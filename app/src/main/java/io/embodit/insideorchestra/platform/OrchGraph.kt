package io.embodit.insideorchestra.platform

import android.content.Context
import io.embodit.insideorchestra.domain.usecase.OrchPose
import io.embodit.insideorchestra.platform.input.ControllerInput
import io.embodit.insideorchestra.platform.sense.PlaneTracker
import io.embodit.insideorchestra.platform.tracking.EyeTracker
import io.embodit.insideorchestra.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services
 * (every start has a stop: see `OrchScene.attach` / `detach`).
 */
object OrchGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val eyes = EyeTracker()
    val controller = ControllerInput()

    @Volatile var appContext: Context? = null

    /** Set by the running scene: the orchestra 1.5 m ahead of the viewer at [scale] (debug `place=auto`). */
    @Volatile var autoPlacement: ((scale: Float) -> OrchPose?)? = null

    /** Set by the running scene: the ghost's current placeable pose (debug `place=wall`), else null. */
    @Volatile var ghostPlacement: (() -> OrchPose?)? = null

    /** Set by the running scene: debug `marker=<m>` puts the floor marker that far ahead (QA stands in for a ray). */
    @Volatile var debugMarker: ((Float) -> Boolean)? = null

    /** Set by the running scene: drags the side panel by (dx, dy) dp; (NaN, NaN) = released (debug `sidedrag=dx,dy`). */
    @Volatile var sideDrag: ((Float, Float) -> Unit)? = null
}
