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
