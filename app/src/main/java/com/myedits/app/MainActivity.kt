package com.myedits.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.myedits.app.ui.editor.EditorScreen
import com.myedits.app.ui.home.HomeScreen
import com.myedits.app.ui.home.HomeViewModel
import com.myedits.app.ui.settings.SettingsScreen
import com.myedits.app.ui.theme.MyEditsTheme

/** Routes are plain strings so they survive process death through rememberSaveable. */
private const val HOME = "home"
private const val SETTINGS = "settings"
private fun editorRoute(id: String, camera: Boolean) = "editor:$id:${if (camera) 1 else 0}"

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { MyEditsTheme { AppRoot() } }
        if (BuildConfig.DEBUG) handleDebugSeed(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.DEBUG) handleDebugSeed(intent)
    }

    /**
     * Debug builds only: `adb shell am start -n ... --esa seed_media /sdcard/a.mp4,/sdcard/b.jpg`
     * creates a project from local files so end-to-end tests don't have to drive the system picker.
     */
    private fun handleDebugSeed(intent: android.content.Intent?) {
        val paths = intent?.getStringArrayExtra("seed_media") ?: return
        intent.removeExtra("seed_media")
        val uris = paths.map { android.net.Uri.fromFile(java.io.File(it)) }
        androidx.lifecycle.ViewModelProvider(this, HomeViewModel.Factory(container))[HomeViewModel::class.java].createFromUris(uris)
    }
}

@UnstableApi
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AppRoot() {
    var route by rememberSaveable { mutableStateOf(HOME) }
    val app = LocalContext.current.container
    val homeVm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(app))
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black).semantics { testTagsAsResourceId = true }) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                if (targetState == HOME) (fadeIn() togetherWith slideOutHorizontally { it / 4 } + fadeOut())
                else (slideInHorizontally { it / 4 } + fadeIn() togetherWith fadeOut())
            },
            label = "route",
        ) { r ->
            when {
                r == HOME -> HomeScreen(
                    onOpenProject = { id, cam -> route = editorRoute(id, cam) },
                    onSettings = { route = SETTINGS },
                )
                r == SETTINGS -> SettingsScreen(onBack = { route = HOME }, onDeleteAll = { homeVm.deleteAll(); route = HOME })
                r.startsWith("editor:") -> {
                    val parts = r.split(":")
                    EditorScreen(projectId = parts[1], openCameraOnStart = parts.getOrNull(2) == "1", onExit = { route = HOME })
                }
            }
        }
    }
}
