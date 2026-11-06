package io.embodit.insideorchestra

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.insideorchestra.ui.orch.OrchScreen
import io.embodit.insideorchestra.ui.theme.OrchTheme
import io.embodit.insideorchestra.ui.window.OrchWindow
import io.embodit.insideorchestra.ui.window.PANEL_ID
import io.embodit.insideorchestra.ui.window.WINDOW_H
import io.embodit.insideorchestra.ui.window.WINDOW_W

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`) holds the orchestra; v0.1.5 (profile
 * D8 / D21, user 2026-11-05: "所有 UI 都集中在一个面板上…bring here…可以拖动的 native tab bar") every control is in one
 * system Planar window opened from the Stage, with the system TabBar for its pages and the system's own move / close /
 * minimise controls (factory snippet native-window-move).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            OrchTheme {
                OrchScreen()
            }
        }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = WINDOW_W.dp, height = WINDOW_H.dp),
        ) { OrchWindow() }
    }
