package io.embodit.insideorchestra.ui.orch

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.insideorchestra.platform.EyePermissionActivity
import io.embodit.insideorchestra.platform.OrchLog
import io.embodit.insideorchestra.ui.orch.components.OrchStage
import io.embodit.insideorchestra.ui.orch.debug.OrchProbe
import io.embodit.insideorchestra.ui.orch.scene.OrchFrameSystem
import io.embodit.insideorchestra.ui.orch.scene.OrchScene

/**
 * The one screen of the app, living in a Mixed Stage. The ViewModel holds the state and the piece clock, the
 * [OrchScene] turns state into entities and reports taps, drags, pointing, the stick and the head, and
 * [OrchStage] declares the panels.
 */
@Composable
fun OrchScreen() {
    val context = LocalContext.current
    val viewModel: OrchViewModel = viewModel(factory = OrchViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        OrchProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { OrchScene(viewModel, viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<OrchFrameSystem>()
        EyePermissionActivity.askOnce(context.applicationContext)
        OrchProbe.host =
            object : OrchProbe.Host {
                override fun dispatch(event: OrchEvent) = viewModel.onEvent(event)

                override fun uiState(): OrchUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): OrchProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = OrchProbe.click(name)
            }
        OrchLog.i("STAGE enter")
        onDispose {
            OrchProbe.host = null
            unregisterSystem<OrchFrameSystem>()
            scene.detach()
            OrchLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, scene) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            scene.onForeground(false)
                            viewModel.onEvent(OrchEvent.WentBackground)
                        }
                        Lifecycle.Event.ON_START -> {
                            scene.onForeground(true)
                            viewModel.onEvent(OrchEvent.CameForeground)
                        }
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: OrchUiEffect ->
            when (effect) {
                OrchUiEffect.ExitApp -> {
                    OrchLog.i("STAGE close requested")
                    scene.audioPause()
                    runCatching { navigator.closeStage() }.onFailure { OrchLog.e("STAGE close failed: $it", it) }
                }
                OrchUiEffect.Click -> runCatching { audio.playSystem(SpatialSoundEffect.OpClick) }
                is OrchUiEffect.Pull -> scene.pull(effect.seatId)
                OrchUiEffect.Home -> scene.goHome()
                is OrchUiEffect.Orbit -> scene.orbit(effect.degrees)
                is OrchUiEffect.Push -> scene.push(effect.metres)
                is OrchUiEffect.Stand -> scene.stand(effect.stem)
                OrchUiEffect.Recalibrate -> scene.recalibrate()
                OrchUiEffect.Finale -> scene.finale()
                is OrchUiEffect.AudioPlay -> scene.audioPlay(effect.fromS)
                OrchUiEffect.AudioPause -> scene.audioPause()
                is OrchUiEffect.AudioSeek -> scene.audioSeek(effect.s)
                OrchUiEffect.AudioRetry -> scene.audioRetry()
                OrchUiEffect.RecallSide -> scene.recallSide()
                is OrchUiEffect.StartMove -> scene.startMove(effect.pose)
                OrchUiEffect.ClearMarker -> scene.clearMarker()
            }
        }
    }

    OrchStage(state = state, piece = viewModel.piece, scene = scene, onEvent = viewModel::onEvent)
}
