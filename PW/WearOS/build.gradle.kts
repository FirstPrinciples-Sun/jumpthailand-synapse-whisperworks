plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("kotlin-parcelize")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

android {
    namespace = "com.yourdomain.whisperworks.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourdomain.whisperworks.wear"
        minSdk = 30  // Wear OS 3.0+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Wear OS specific configuration
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Performance optimizations
            isDebuggable = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        
        // Enable core library desugaring for older API levels
        isCoreLibraryDesugaringEnabled = true
    }
    
    kotlinOptions {
        jvmTarget = "17"
        
        // Enable experimental features
        freeCompilerArgs += listOf(
            "-opt-in=androidx.wear.compose.material.ExperimentalWearMaterialApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "DebugProbesKt.bin"
            )
        }
    }
}

dependencies {
    // CORE DESUGARING SUPPORT
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    
    // CORE ANDROIDX LIBRARIES
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // JETPACK COMPOSE for WEAR OS (BOM)
    val wearComposeBom = platform("androidx.wear.compose:compose-bom:2024.10.00")
    implementation(wearComposeBom)
    androidTestImplementation(wearComposeBom)

    // WEAR OS UI COMPONENTS
    implementation("androidx.wear.compose:compose-material")
    implementation("androidx.wear.compose:compose-foundation")
    implementation("androidx.wear.compose:compose-ui-tooling-preview")
    implementation("androidx.wear.compose:compose-navigation")
    
    // Wear OS Icons
    implementation("androidx.wear.compose:compose-material-icons:1.0.0")
    
    // Additional Wear Compose components
    implementation("androidx.wear.compose:compose-ui")
    implementation("androidx.wear.compose:compose-ui-graphics")
    
    // Debug tools
    debugImplementation("androidx.wear.compose:compose-ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // WEARABLE DATA LAYER API
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    
    // WEAR OS Health Services (for sensor data)
    implementation("androidx.health:health-services-client:1.1.0-alpha04")
    
    // DAGGER HILT for Dependency Injection
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    
    // KOTLINX COROUTINES
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    
    // KOTLINX SERIALIZATION
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    
    // TIMBER for Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // WEAR OS Complications (for watch face complications)
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
    
    // Permissions handling
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    
    // TESTING LIBRARIES
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    
    // ANDROID TESTING
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.wear.compose:compose-ui-test-junit4")
    
    // DEBUG LIBRARIES
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}