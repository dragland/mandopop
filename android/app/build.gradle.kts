import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    }

    buildFeatures {
        compose = true
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
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
