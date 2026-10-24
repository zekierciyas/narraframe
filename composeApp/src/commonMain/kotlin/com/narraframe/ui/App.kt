package com.narraframe.ui

import androidx.compose.runtime.*
import com.narraframe.ui.setup.SetupWizard
import com.narraframe.ui.theme.StudioTheme
import com.narraframe.viewmodel.AppInitState
import com.narraframe.viewmodel.Screen
import com.narraframe.viewmodel.StudioViewModel

/**
 * App navigation:
 *   Init (ffmpeg)  →  Setup wizard (first launch)  →  Storyboard
 *                                                     ↔ Library
 *                                                     ↔ Settings
 */
@Composable
fun App() {
    val viewModel = remember { StudioViewModel() }
    DisposableEffect(Unit) { onDispose { viewModel.dispose() } }

    StudioTheme {
        when (val s = viewModel.initState) {
            is AppInitState.Loading -> InitScreen(state = s, onRetry = { viewModel.retryInit() })
            is AppInitState.Failed  -> InitScreen(state = s, onRetry = { viewModel.retryInit() })
            AppInitState.Ready -> when (viewModel.screen) {
                Screen.SETUP    -> SetupWizard(viewModel)
                Screen.STUDIO   -> StoryboardScreen(viewModel)
                Screen.LIBRARY  -> LibraryScreen(viewModel)
                Screen.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}
