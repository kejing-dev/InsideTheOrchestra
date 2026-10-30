package io.embodit.insideorchestra.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.insideorchestra.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OrchGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
