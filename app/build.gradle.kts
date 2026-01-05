import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  id("kotlin-parcelize")
}

apply<DeploymentContractPlugin>()

android {
  namespace = "me.fleey.futon"
  compileSdk = 36

  defaultConfig {
    applicationId = "me.fleey.futon"
    minSdk = 30
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    @Suppress("UnstableApiUsage")
    externalNativeBuild {
      cmake {
        cppFlags += "-std=c++17"
        arguments += "-DANDROID_STL=c++_shared"
      }
    }

    ndk {
      //noinspection ChromeOsAbiSupport
      abiFilters += listOf("arm64-v8a")
    }
  }

  signingConfigs {
    create("release") {
      val keystorePropertiesFile = rootProject.file("keystore.properties")
      if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties().apply {
          load(FileInputStream(keystorePropertiesFile))
        }
        storeFile = file(keystoreProperties["storeFile"] as String)
        storePassword = keystoreProperties["storePassword"] as String
        keyAlias = keystoreProperties["keyAlias"] as String
        keyPassword = keystoreProperties["keyPassword"] as String
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val keystorePropertiesFile = rootProject.file("keystore.properties")
      if (keystorePropertiesFile.exists()) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug {
      isMinifyEnabled = false
      isDebuggable = true
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  buildFeatures {
    compose = true
    aidl = true
    buildConfig = true
  }

  sourceSets {
    getByName("main") {
      assets.srcDirs(
        "src/main/assets",
        layout.buildDirectory.dir("generated/assets"),
      )
      aidl.srcDirs(
        "src/main/aidl",
        rootProject.file("aidl"),
      )
    }
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/INDEX.LIST"
      excludes += "/META-INF/io.netty.versions.properties"
    }
    jniLibs {
      useLegacyPackaging = true
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  ndkVersion = "29.0.14206865"
}

dependencies {
  // AndroidX
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)

  // Compose
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)

  // Browser
  implementation(libs.androidx.browser)

  // Circuit
  implementation(libs.circuit.foundation)
  implementation(libs.circuit.runtime)
  implementation(libs.circuit.runtime.presenter)
  implementation(libs.circuit.runtime.ui)
  implementation(libs.circuit.codegen.annotations)
  ksp(libs.circuit.codegen)

  // Koin
  implementation(libs.koin.core)
  implementation(libs.koin.android)
  implementation(libs.koin.androidx.compose)
  implementation(libs.koin.annotations)
  ksp(libs.koin.ksp.compiler)
  ksp(project(":processor"))

  // Ktor Client
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.serialization.kotlinx.json)

  // Ktor Server
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.auth)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.call.logging)

  // Kotlin
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  // Room
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  // Security
  implementation(libs.safebox)

  // UI
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.squircle.shape)

  // System
  implementation(libs.libsu.core)
  implementation(libs.libsu.service)

  // Testing
  testImplementation(libs.junit)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
  testImplementation(libs.kotest.property)
  testImplementation(libs.mockk)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)

  // Debug
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.ktor.client.websockets)
}

tasks.withType<Test> {
  useJUnitPlatform()
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
  arg("room.expandProjection", "true")
  arg("KOIN_CONFIG_CHECK", "true")
  arg("KOIN_DEFAULT_MODULE", "true")
}
