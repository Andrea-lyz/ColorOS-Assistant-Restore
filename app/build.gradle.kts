plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is injected at build time through -Prelease.* properties or, when the shell
// makes those awkward to quote, the matching RELEASE_* environment variables. A plain local
// build and a CI build without secrets both keep working. See .github/workflows for the wiring.
fun signingValue(property: String, environment: String): String? =
    (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environment)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("release.storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("release.storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("release.keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("release.keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "io.github.andrea_lyz.assistrestore"
    // io.github.libxposed:service 102 targets API 37; the platform is installed locally.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.andrea_lyz.assistrestore"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.3"
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Modern Xposed API 102 must stay compile-only; the framework provides it at runtime.
    compileOnly(project(":libxposed-api"))
    compileOnly("androidx.annotation:annotation:1.10.0")

    // UI preview build: Compose + Material 3. Everything resolves from the local Gradle cache.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Framework bridge: the settings UI writes the configuration the hooks read.
    implementation("io.github.libxposed:service:102.0.0")
}

// The build runs with --offline against a shared cache, so every artifact has to land on a
// revision that is actually present there. The Kotlin 2.3.20 Compose compiler also requires a
// newer runtime than the 2024.09.00 BOM pins, and the cache carries exactly 1.10.5 for it.
configurations.configureEach {
    // lifecycle 2.9.x drags in kotlinx-serialization for SavedStateHandle serialization; the
    // preview never serializes state, and the cache only holds part of that module.
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")

    resolutionStrategy.eachDependency {
        val group = requested.group
        val module = requested.name
        when {
            group == "androidx.compose.runtime" -> useVersion("1.10.5")
            group == "androidx.lifecycle" -> useVersion("2.9.4")
            group == "androidx.savedstate" -> useVersion("1.3.3")
            group == "androidx.collection" -> useVersion("1.5.0")
            group == "androidx.annotation" && (module == "annotation" || module == "annotation-jvm") ->
                useVersion("1.10.0")
        }
    }
}
