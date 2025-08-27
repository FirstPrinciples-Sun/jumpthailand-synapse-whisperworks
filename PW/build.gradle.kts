plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("kotlin-parcelize")
}

android {
    namespace = "com.yourdomain.whisperworks"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourdomain.whisperworks"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // Optimize for performance
        renderscriptTargetApi = 35
        renderscriptSupportModeEnabled = false
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
            isJniDebuggable = false
            renderscriptDebuggable = false
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
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = false
        dataBinding = false
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
    
    // Optimize build performance
    androidResources {
        noCompress += setOf("tflite", "model")
    }
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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

    // JETPACK COMPOSE (BOM - Bill of Materials)
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // JETPACK COMPOSE UI COMPONENTS
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    
    // COMPOSE NAVIGATION
    implementation("androidx.navigation:navigation-compose:2.8.2")
    
    // COMPOSE LIFECYCLE
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // WEARABLE DATA LAYER API
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    
    // KTOR FOR NETWORKING
    val ktorVersion = "2.3.12"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")

    // KOTLINX SERIALIZATION
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    
    // KOTLINX COROUTINES
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    
    // LOGGING
    implementation("com.jakewharton.timber:timber:5.0.1")

    // TESTING LIBRARIES
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    
    // ANDROID TESTING
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")

    // DEBUG LIBRARIES
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}