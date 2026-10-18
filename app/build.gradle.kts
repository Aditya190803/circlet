plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.circlet"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.circlet"
        minSdk = 31
        targetSdk = 35
        versionCode = 15
        versionName = "0.4.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notice"))
}

// Full builds compile the real backend wiring in src/full against :data, :strap
// and :analytics, plus the debug-only demo seeding in src/seedDebug (which needs
// the debug-only DemoData). Core-only builds (the UI against :core and its fakes)
// skip both; AppBootstrap then falls back to the fake through a class-path probe.
if (!providers.gradleProperty("circlet.coreOnly").isPresent) {
    android.sourceSets.getByName("main").kotlin.srcDir("src/full/kotlin")
    android.sourceSets.getByName("debug").kotlin.srcDir("src/seedDebug/kotlin")
}

// The About screen renders the repository's NOTICE.md, so it is packaged as an asset from its one source.
val packageNotice = tasks.register<Copy>("packageNotice") {
    from(rootProject.file("NOTICE.md"))
    into(layout.buildDirectory.dir("generated/notice"))
}
tasks.named("preBuild") { dependsOn(packageNotice) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // -Pcirclet.coreOnly builds the UI against :core and its fakes alone, for when a backend module is mid-change.
    if (!providers.gradleProperty("circlet.coreOnly").isPresent) {
        implementation(project(":protocol"))
        implementation(project(":analytics"))
        implementation(project(":data"))
        implementation(project(":strap"))
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation("androidx.health.connect:connect-client:1.1.0-alpha12")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7")

    testImplementation(libs.junit)
}

// The app must never declare the INTERNET permission: nothing leaves the phone
// except through explicit export. This fails the build if any manifest
// (including a library's) merges it in.
val checkNoInternetPermission = tasks.register("checkNoInternetPermission") {
    val manifests = listOf("debug", "release").map { variant ->
        layout.buildDirectory.file("intermediates/merged_manifests/$variant/AndroidManifest.xml")
    }
    inputs.files(manifests)
    dependsOn("processDebugMainManifest", "processReleaseMainManifest")
    doLast {
        for (manifest in manifests.map { it.get().asFile }) {
            if (manifest.isFile && manifest.readText().contains("android.permission.INTERNET")) {
                throw GradleException("INTERNET permission present in ${manifest.path}")
            }
        }
    }
}
tasks.named("check") { dependsOn(checkNoInternetPermission) }
