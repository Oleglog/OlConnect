import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "io.github.oleglog.olcrtc.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.oleglog.olcrtc.client"
        minSdk = 26
        targetSdk = 36
        versionCode = 95
        versionName = "1.6.5"
        val expectedSigningCertSha256 = providers.gradleProperty("androidSigningCertSha256")
            .orElse(providers.environmentVariable("ANDROID_SIGNING_CERT_SHA256"))
            .orNull
            .orEmpty()
        buildConfigField(
            "String",
            "EXPECTED_SIGNING_CERT_SHA256",
            "\"$expectedSigningCertSha256\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.isFile) {
                storeFile = rootProject.file(requireNotNull(keystoreProperties["storeFile"]) { "storeFile is required" })
                storePassword = requireNotNull(keystoreProperties["storePassword"]) { "storePassword is required" }.toString()
                keyAlias = requireNotNull(keystoreProperties["keyAlias"]) { "keyAlias is required" }.toString()
                keyPassword = requireNotNull(keystoreProperties["keyPassword"]) { "keyPassword is required" }.toString()
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }

    buildTypes {
        debug {
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        release {
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        aidl = true
        viewBinding = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(files("libs/mobilecore.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation(libs.room.runtime)
    implementation(libs.zxing.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.datastore.preferences)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.recyclerview)
    implementation(libs.viewpager2)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.test.core.ktx)
    androidTestImplementation(libs.room.testing)
}
