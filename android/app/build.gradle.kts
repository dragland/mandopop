import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mandopop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mandopop"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Traverse's public Firebase web config. Not a secret — Google serves these to every web
        // client and they authorise nothing on their own — but they are deployment configuration
        // rather than program logic, so they live here and can be overridden with
        // -Ptraverse.apiKey=... without touching source. Only the optional Traverse sync reads
        // them; the dictionary and the Chrome extension never do.
        buildConfigField(
            "String",
            "TRAVERSE_API_KEY",
            "\"${findProperty("traverse.apiKey") ?: "AIzaSyAsG5pbllBxykmI8Gd94-zwB0WouEVg6y0"}\"",
        )
        buildConfigField(
            "String",
            "TRAVERSE_PROJECT_ID",
            "\"${findProperty("traverse.projectId") ?: "alley-d0944"}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "db"
    }

    lint {
        disable += "StateFlowValueCalledInComposition"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val dictionaryInput = rootProject.layout.projectDirectory.file("../cedict.json")
val generatedDictionaryDir = layout.buildDirectory.dir("generated/assets/dictionary")
val dictionaryOutput = generatedDictionaryDir.map { it.file("cedict.db") }
val dictionaryHashOutput = generatedDictionaryDir.map { it.file("cedict.sha256") }
val dictionaryScript = rootProject.layout.projectDirectory.file("scripts/build_dictionary.py")
val sharedTestdataDir = rootProject.layout.projectDirectory.dir("../testdata")

android.sourceSets["main"].assets.srcDir(generatedDictionaryDir)

tasks.register<Exec>("buildDictionary") {
    inputs.file(dictionaryInput)
    inputs.file(dictionaryScript)
    outputs.file(dictionaryOutput)
    outputs.file(dictionaryHashOutput)

    commandLine(
        "python3",
        dictionaryScript.asFile.absolutePath,
        dictionaryInput.asFile.absolutePath,
        dictionaryOutput.get().asFile.absolutePath,
    )
}

tasks.named("preBuild") {
    dependsOn("buildDictionary")
}

tasks.withType<Test>().configureEach {
    inputs.dir(sharedTestdataDir)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Token storage. EncryptedSharedPreferences is deprecated; DataStore + Tink is its
    // replacement, and Tink handles the per-OEM Keystore brittleness that deprecated it.
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.crypto.tink:tink-android:1.15.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Android's bundled org.json is a stub in unit tests; the real implementation lets the
    // Firestore decoder be tested on the JVM.
    testImplementation("org.json:json:20240303")

    // Room queries are only meaningfully testable against real SQLite. Kept to the queries whose
    // silent breakage would be hard to notice; everything else is a plain unit test.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
