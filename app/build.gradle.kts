import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hexo141.liquidglass"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hexo141.liquidglass"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs {
            // conscrypt-android ships .so files that are not 16KB-page aligned; extracting them
            // at install time avoids load failures on 16KB page size devices
            useLegacyPackaging = true
        }
    }
}

// ---------------------------------------------------------------------------
// Temporary root: build the arm64 exploit payload from native/ with the NDK and
// drop it into jniLibs so it is extracted into nativeLibraryDir at install time.
// ---------------------------------------------------------------------------

/** NDK location: local.properties -> ANDROID_NDK_HOME -> NDK_ROOT -> D:\android-ndk-r27d. */
fun resolveNdkDir(): String {
    val properties = Properties()
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(properties::load)
    return sequenceOf(
        properties.getProperty("ndk.dir"),
        System.getenv("ANDROID_NDK_HOME"),
        System.getenv("NDK_ROOT"),
        "D:/android-ndk-r27d",
    ).firstOrNull { !it.isNullOrBlank() }!!
}

val ndkDir: String = resolveNdkDir()
val ndkPrebuilt: String = when {
    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkBinDir: File = File(ndkDir, "toolchains/llvm/prebuilt/$ndkPrebuilt/bin")
val ndkMake: String = File(ndkDir, "prebuilt/$ndkPrebuilt/bin/${if (ndkPrebuilt == "windows-x86_64") "make.exe" else "make"}").absolutePath
val ndkStrip: String = File(ndkBinDir, if (ndkPrebuilt == "windows-x86_64") "llvm-strip.exe" else "llvm-strip").absolutePath

val nativeSourceDir = rootProject.layout.projectDirectory.dir("native")
val nativeBinary = rootProject.layout.buildDirectory.file("native/ghostlock")
val packagedPayload = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libghostlock.so")

val buildNativeExploit = tasks.register<Exec>("buildNativeExploit") {
    group = "build"
    description = "Compiles the arm64 exploit payload with the NDK"
    workingDir = nativeSourceDir.asFile
    commandLine(ndkMake, "ghostlock", "-j4")
    environment("NDK_ROOT", ndkDir)
    inputs.dir(nativeSourceDir.dir("core"))
    inputs.file(nativeSourceDir.file("Makefile"))
    outputs.file(nativeBinary)
}

val prepareNativeExploit = tasks.register<Copy>("prepareNativeExploit") {
    group = "build"
    description = "Copies the exploit payload into jniLibs"
    dependsOn(buildNativeExploit)
    from(nativeBinary)
    into(layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a"))
    rename { "libghostlock.so" }
}

val stripNativeExploit = tasks.register<Exec>("stripNativeExploit") {
    group = "build"
    description = "Strips the packaged exploit payload"
    dependsOn(prepareNativeExploit)
    commandLine(ndkStrip, "--strip-all", packagedPayload.asFile.absolutePath)
    inputs.file(packagedPayload)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(stripNativeExploit)
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Compose 基础库：backdrop 的 compose 依赖是 runtime 作用域，编译期需要显式声明
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)

    // Liquid Glass 按钮（Compose，同时带入 shapes）
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)

    // Wireless debugging pairing (SPAKE2 / HKDF / AES-GCM)
    implementation(libs.androidx.annotation)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    // TLSv1.3 exportKeyingMaterial without relying on hidden APIs
    implementation(libs.conscrypt.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
