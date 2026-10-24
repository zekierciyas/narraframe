package com.narraframe

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.narraframe.ui.components.appBackground
import com.narraframe.ui.theme.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.narraframe.ui.LibraryScreen
import com.narraframe.ui.SettingsScreen
import com.narraframe.ui.StoryboardScreen
import com.narraframe.ui.setup.SetupWizard
import com.narraframe.ui.theme.StudioTheme
import com.narraframe.viewmodel.StudioViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the 1280×640 GitHub social preview image, with [screenshot] (an app window
 * capture) on the right:
 *
 *   ./gradlew :composeApp:run -PsocialPreview=docs/media/social-preview.png -PsocialShot=window.png
 */
internal fun renderSocialPreview(outPath: String, screenshot: String): Int {
    val shot = org.jetbrains.skia.Image.makeFromEncoded(File(screenshot).readBytes()).toComposeImageBitmap()
    val scene = ImageComposeScene(width = 1280, height = 640, density = Density(1f)) {
        StudioTheme { SocialPreview(shot) }
    }
    repeat(5) { scene.render(it * 16_000_000L) }
    File(outPath).also { it.parentFile?.mkdirs() }
        .writeBytes(scene.render(100_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("[social] $outPath")
    return 0
}

@androidx.compose.runtime.Composable
private fun SocialPreview(shot: androidx.compose.ui.graphics.ImageBitmap) {
    Box(Modifier.fillMaxSize().appBackground()) {
        // App window, tilted slightly and running off the right edge
        Image(
            shot, null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 170.dp, y = 30.dp)
                .width(760.dp)
                .graphicsLayer { rotationZ = -4f }
                .shadow(40.dp, RoundedCornerShape(14.dp), ambientColor = Accent, spotColor = Accent)
                .clip(RoundedCornerShape(14.dp))
        )
        Column(Modifier.align(Alignment.CenterStart).padding(start = 72.dp).width(560.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(AccentGradient))
                Spacer(Modifier.width(16.dp))
                Text("Narraframe", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = TextPrimary, letterSpacing = (-1).sp)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "Turn any script into a narrated AI video",
                fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "100% local AI on your Mac. No cloud, no API keys.",
                fontSize = 21.sp, color = TextMuted
            )
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Kotlin Multiplatform", strong = true)
                Pill("Compose Multiplatform")
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("llama.cpp")
                Pill("Stable Diffusion")
                Pill("Neural TTS")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Pill(text: String, strong: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .then(if (strong) Modifier.background(AccentGradient) else Modifier.background(Surface2))
            .border(1.dp, if (strong) Color.Transparent else Outline, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (strong) OnAccent else TextPrimary)
    }
}

/**
 * Renders the main screens off-screen to PNG files, for checking the UI without a display:
 *
 *   ./gradlew :composeApp:run -Pscreenshots=/tmp/nf-shots
 */
internal fun renderScreenshots(outDir: String): Int = runBlocking {
    val dir = File(outDir).also { it.mkdirs() }
    val vm = StudioViewModel()
    vm.script = "On a rocky island far from the coast, an old lighthouse keeper named Elias lived alone with his dog. " +
        "Every evening he climbed the spiral stairs and lit the great lamp."
    val screens = listOf<Pair<String, @androidx.compose.runtime.Composable () -> Unit>>(
        "storyboard" to { StoryboardScreen(vm) },
        "settings"   to { SettingsScreen(vm) },
        "library"    to { LibraryScreen(vm) },
        "setup"      to { SetupWizard(vm) }
    )
    for ((name, content) in screens) {
        val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) {
            StudioTheme { remember { 0 }; content() }
        }
        // Let effects, image loads and animations settle
        repeat(30) { i -> scene.render(i * 50_000_000L); delay(40) }
        val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(dir, "$name.png").writeBytes(png)
        scene.close()
        println("[screenshots] $name.png")
    }
    vm.dispose()
    0
}
