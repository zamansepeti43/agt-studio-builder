plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vendorRoot = layout.buildDirectory.dir("vendor/iappyxOS")
val enginePackage = project.file("src/main/java/com/iappyx/container")
val assetsDir = project.file("src/main/assets")
val shellOut = assetsDir.resolve("shell_template.apk")

val prepareEngine = tasks.register("prepareOnDeviceEngine") {
    outputs.dir(enginePackage)
    outputs.file(shellOut)
    doLast {
        val rootDir = vendorRoot.get().asFile
        val repo = File(rootDir, "repo")
        rootDir.mkdirs()
        if (!repo.isDirectory) {
            exec {
                commandLine("git", "clone", "--depth", "1", "https://github.com/iappyx/iappyxOS.git", repo.absolutePath)
            }
        }
        val src = File(repo, "src/container_app/android/app/src/main/kotlin/com/iappyx/container")
        check(src.isDirectory) { "iappyxOS injector source not found" }
        enginePackage.mkdirs()
        listOf("ApkInjector.kt", "KeyManager.kt", "ApkInstaller.kt").forEach { name ->
            val f = File(src, name)
            check(f.isFile) { "Missing engine source: $name" }
            f.copyTo(File(enginePackage, name), overwrite = true)
        }

        val shell = File(repo, "src/shell_apk")
        check(shell.isDirectory) { "iappyxOS shell project not found" }
        val wrapper = if (System.getProperty("os.name").lowercase().contains("win")) "gradlew.bat" else "./gradlew"
        exec {
            workingDir(shell)
            commandLine(wrapper, "assembleRelease", "-q")
        }
        val built = File(shell, "app/build/outputs/apk/release/app-release.apk")
        check(built.isFile) { "shell_template.apk could not be built" }
        assetsDir.mkdirs()
        built.copyTo(shellOut, overwrite = true)
    }
}

tasks.named("preBuild") { dependsOn(prepareEngine) }

android {
    namespace = "com.agtstudio.zipapk"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.agtstudio.zipapk"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "3.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
