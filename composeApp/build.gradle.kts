import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("app.cash.sqldelight") version "2.3.2"
}
repositories {
    google()
    mavenCentral()
}

sqldelight {
    databases {
        create("MeshovikDatabase") {
            packageName.set("com.meshovik.database")
            // dialect = "app.cash.sqldelight:dialects.sqlite" // если нужно явно
        }
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Koin DI
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            // Kable BLE
            implementation(libs.kable.core)
            implementation("cafe.adriel.voyager:voyager-koin:2.2.21-1.10.3")
            // Kotlinx Serialization
            implementation(libs.kotlinx.serialization.json)

            // Kotlinx Coroutines
            implementation(libs.kotlinx.coroutines.core)

            // Kotlinx DateTime
            implementation(libs.kotlinx.datetime)
            implementation("app.cash.sqldelight:runtime:2.0.2")
            implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
        }

        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.3.2")
            implementation(compose.uiTooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)

            // Koin Android
            implementation(libs.koin.android)
            implementation(libs.koin.androidx.compose)

            // Kotlinx Coroutines Android
            implementation(libs.kotlinx.coroutines.android)

            // Timber logging
            implementation(libs.timber)
            implementation("com.google.android.gms:play-services-nearby:19.3.0")
            // Voyager Navigation
            implementation(libs.voyager.navigator)
            implementation("cafe.adriel.voyager:voyager-koin:2.2.21-1.10.3")
            implementation(libs.voyager.screen.model)
            implementation(libs.voyager.transitions)
            implementation(libs.compose.material3)
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")

            // Coil — загрузка изображений (URI, file://, content://)
            implementation(libs.coil.compose)
        }

        iosMain.dependencies {
            // iOS-specific dependencies if needed
        }
    }
}

android {
    namespace = "com.meshovik"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.meshovik"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
