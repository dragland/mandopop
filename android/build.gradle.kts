plugins {
    id("com.android.application") version "8.7.3" apply false
    // Kotlin 2.2: the ML Kit GenAI Prompt artifacts ship 2.2 metadata, which a 2.0 compiler
    // refuses to read. KSP tracks the Kotlin version by construction.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
