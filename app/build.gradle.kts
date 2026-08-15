plugins {
    alias(libs.plugins.android.application)
}

import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.example.devicetracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.devicetracker"
        minSdk = 26
        targetSdk = 37
        versionCode = versionProps.getProperty("versionCode", "1").toInt()
        versionName = versionProps.getProperty("versionName", "1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile", ""))
            storePassword = keystoreProps.getProperty("storePassword", "")
            keyAlias = keystoreProps.getProperty("keyAlias", "")
            keyPassword = keystoreProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.paho.mqtt)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

tasks.register("bumpVersion") {
    group = "versioning"
    description = "Increment versionCode and patch versionName in version.properties"
    doLast {
        val f = rootProject.file("version.properties")
        val vc = versionProps.getProperty("versionCode", "1").toIntOrNull() ?: 1
        val vn = versionProps.getProperty("versionName", "1.0")
        val parts = vn.split(".")
        val maj = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val pat = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val newVc = vc + 1
        val newVn = "$maj.$min.${pat + 1}"
        Properties().apply {
            setProperty("versionCode", newVc.toString())
            setProperty("versionName", newVn)
        }.let { p ->
            FileOutputStream(f).use { p.store(it, "Android release versioning") }
        }
        println("Bumped to versionCode=$newVc versionName=$newVn")
    }
}