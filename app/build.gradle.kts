import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.rodinrescue"
    compileSdk = 35
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.rodinrescue"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.2"
        // Built-in Ubuntu (PRoot) is arm64-only; both test phones are arm64.
        ndk.abiFilters += "arm64-v8a"
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // Release key lives OUTSIDE the repo (../release/keystore.properties next to the clone, or LIFELINE_SIGNING=path).
    // Without it, release builds are produced unsigned.
    val signingProps = Properties().apply {
        val f = System.getenv("LIFELINE_SIGNING")?.let { file(it) } ?: rootDir.parentFile.resolve("release/keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Debuggable, signed with the release key when it's here: installs over the release app without losing its data.
        create("dev") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
    }
    check(!buildTypes.getByName("release").isDebuggable) { "The release build must never be debuggable (use the dev build)" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        // PRoot and its loader ship as lib*.so so Android extracts them to nativeLibraryDir, the one app folder it lets us run programs from.
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += setOf(
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/versions/**",
                "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/INDEX.LIST",
                "META-INF/BCKEY.*", "META-INF/BC*.SF", "META-INF/BC*.DSA", "META-INF/BC*.RSA"
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric pulls Fragment 1.2.5, which crashes Activity Result launchers (file picker) on a FragmentActivity
    // ("Can only use lower 16 bits for requestCode"); 1.3+ integrates with the result registry.
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Keyboard rescue mode: the camera is the AI's eyes (photos go to the AI brain).
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Unpacking Ubuntu Base for the built-in runtime (tar; gzip comes from java.util.zip).
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.anthropic:anthropic-java:2.34.0")

    implementation("com.hierynomus:sshj:0.39.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.slf4j:slf4j-nop:2.0.13")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.apache.sshd:sshd-core:2.13.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("org.apache.sshd:sshd-netty:2.13.1")
    testImplementation("org.slf4j:slf4j-nop:2.0.13")
}
