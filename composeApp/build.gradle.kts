import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // ── Desktop (JVM) ──────────────────────────────────────────────────────
    jvm("desktop")

    // ── Source sets ────────────────────────────────────────────────────────
    sourceSets {

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

// ── Compose Desktop packaging ──────────────────────────────────────────────
compose.desktop {
    application {
        mainClass = "com.narraframe.MainKt"

        // ── JVM tuning ─────────────────────────────────────────────────────
        jvmArgs(
            "-Xms128m",
            "-Xmx2g",
            "-Dfile.encoding=UTF-8",
            "-Dsun.java2d.uiScale=1.0"   // prevent HiDPI double-scaling on some Linux setups
        )

        // Show the setup wizard even when setup is done: ./gradlew :composeApp:run -PforceSetup
        if (project.hasProperty("forceSetup")) jvmArgs("-Dnarraframe.forceSetup=true")

        // Run one generation without the UI (see Headless.kt):
        //   ./gradlew :composeApp:run -PheadlessScript=script.txt [-PheadlessOut=dir] [-PheadlessSdModel=id]
        listOf("headlessScript", "headlessOut", "headlessSdModel", "headlessResume", "screenshots", "socialPreview", "socialShot").forEach { key ->
            (project.findProperty(key) as String?)?.let { jvmArgs("-Dnarraframe.$key=$it") }
        }

        // ── Debug mode (enabled with: ./gradlew :composeApp:run -PdebugJvm) ─
        // Attach IntelliJ / any JDWP client to localhost:5005 after launch.
        // Use -PsuspendJvm to make the JVM wait for the debugger before starting.
        if (project.hasProperty("debugJvm")) {
            val suspend = if (project.hasProperty("suspendJvm")) "y" else "n"
            jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=$suspend,address=*:5005")
            jvmArgs("-Ddebug.port=5005")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // JDK modules the bundled runtime must contain (from ./gradlew suggestRuntimeModules).
            // Without java.net.http the image engine client fails only in packaged builds.
            modules("java.instrument", "java.net.http", "java.prefs", "jdk.management", "jdk.unsupported")
            packageName        = "Narraframe"
            packageVersion     = (findProperty("appVersion") as String?) ?: "1.0.0"
            description        = "Turn any script into a narrated AI video"
            copyright          = "© 2026 Zeki Erciyas"
            vendor             = "Zeki Erciyas"

            macOS {
                bundleID = "com.narraframe.app"
            }
        }
    }
}
