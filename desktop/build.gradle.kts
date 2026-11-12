import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.regex.Pattern

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Legge versionMajor/versionMinor/versionPatch da app/build.gradle.kts (Single Source of Truth)
fun readAppVersion(): Triple<Int, Int, Int> {
    val appGradle = rootProject.file("app/build.gradle.kts").readText()
    fun extract(name: String, defaultVal: Int): Int {
        val m = Pattern.compile("""val\s+$name\s*=\s*(\d+)""").matcher(appGradle)
        return if (m.find()) m.group(1).toInt() else defaultVal
    }
    return Triple(
        extract("versionMajor", 3),
        extract("versionMinor", 0),
        extract("versionPatch", 0)
    )
}

val (vMajor, vMinor, vPatch) = readAppVersion()
val numericVersion = "$vMajor.$vMinor.$vPatch"
val fullVersionName = "v$numericVersion"
val fullVersionCode = vMajor * 10000 + vMinor * 100 + vPatch

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/source/buildInfo/main")

val generateDesktopBuildInfo = tasks.register("generateDesktopBuildInfo") {
    val outDir = generatedBuildInfoDir
    val vName = fullVersionName
    val vCode = fullVersionCode
    val numVer = numericVersion
    inputs.property("versionName", vName)
    inputs.property("versionCode", vCode)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("com/example/crossreel/desktop")
        pkgDir.mkdirs()
        pkgDir.resolve("DesktopBuildConfig.kt").writeText(
            """
            package com.example.crossreel.desktop

            object DesktopBuildConfig {
                const val VERSION_NAME = "$vName"
                const val VERSION_CODE = $vCode
                const val NUMERIC_VERSION = "$numVer"
            }
            """.trimIndent()
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir(generatedBuildInfoDir)
        resources {
            // Riusa le 16 tracce audio offline senza duplicarle nel repository
            srcDir(rootProject.file("app/src/main/res"))
            include("raw/*.mp3")
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateDesktopBuildInfo)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "com.example.crossreel.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "OrangeBeat"
            packageVersion = numericVersion
            description = "Orange-Beat — Studio di Montaggio Video AI 100% On-Device"
            vendor = "Marco Newton"

            linux {
                packageName = "orangebeat"
                debPackageVersion = numericVersion
                menuGroup = "AudioVideo"
                appCategory = "Video"
            }

            windows {
                packageVersion = numericVersion
                msiPackageVersion = numericVersion
                exePackageVersion = numericVersion
                menuGroup = "OrangeBeat"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}
