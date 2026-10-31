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
        versionCode = 32
        versionName = "0.6.5"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        // A side-by-side install for trying changes: its own package, so the real app's data is never touched.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += "release"
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

    // The dev install behaves like release: no demo seeding, no debug gallery.
    sourceSets.getByName("dev").kotlin.srcDir("src/release/kotlin")
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notice"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/firebase"))
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

val firebaseConfig = layout.projectDirectory.file("google-services.json")
val firebaseAssets = layout.buildDirectory.dir("generated/firebase")
val personalDatabaseUrl = "https://whoop-circlet-default-rtdb.asia-southeast1.firebasedatabase.app"
val packageFirebaseProject = tasks.register("packageFirebaseProject") {
    inputs.files(firebaseConfig).optional()
    inputs.property("databaseUrl", personalDatabaseUrl)
    outputs.dir(firebaseAssets)
    doLast {
        val source = firebaseConfig.asFile
        val bundled = if (source.isFile) {
            val json = groovy.json.JsonSlurper().parse(source) as Map<*, *>
            val project = json["project_info"] as? Map<*, *> ?: error("Invalid Firebase Android configuration")
            check(project["project_id"] == "whoop-circlet") { "Firebase configuration must belong to whoop-circlet" }
            val client = (json["client"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull {
                val info = it["client_info"] as? Map<*, *>
                val androidInfo = info?.get("android_client_info") as? Map<*, *>
                androidInfo?.get("package_name") == "app.circlet"
            } ?: error("Register the Android app with package app.circlet and download google-services.json again")
            val apiKey = (client["api_key"] as? List<*>)?.filterIsInstance<Map<*, *>>()
                ?.mapNotNull { it["current_key"] as? String }?.firstOrNull { it.matches(Regex("AIza[A-Za-z0-9_-]{35}")) }
                ?: error("The Firebase Android configuration has no valid API key")
            mapOf("url" to personalDatabaseUrl, "api_key" to apiKey)
        } else emptyMap<String, String>()
        val destination = firebaseAssets.get().file("firebase-project.json").asFile
        destination.parentFile.mkdirs()
        // Always overwrite when the source is removed so a previous project's key cannot reach another build.
        destination.writeText(groovy.json.JsonOutput.toJson(bundled))
    }
}
tasks.named("preBuild") { dependsOn(packageFirebaseProject) }

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

// Personal cloud access requires networking; plaintext transport remains disabled.
val checkCloudNetworkPolicy = tasks.register("checkCloudNetworkPolicy") {
    val manifests = listOf("debug", "release").map { variant ->
        layout.buildDirectory.file("intermediates/merged_manifests/$variant/process${variant.replaceFirstChar { it.uppercaseChar() }}Manifest/AndroidManifest.xml")
    }
    inputs.files(manifests)
    dependsOn("processDebugManifest", "processReleaseManifest")
    doLast {
        for (manifest in manifests.map { it.get().asFile }) {
            check(manifest.isFile) { "Merged manifests were not generated" }
            val xml = manifest.readText()
            check(xml.contains("android.permission.INTERNET")) { "Cloud networking permission is missing" }
            check(xml.contains("android:usesCleartextTraffic=\"false\"")) { "Plaintext cloud traffic must remain disabled" }
        }
    }
}
tasks.named("check") { dependsOn(checkCloudNetworkPolicy) }
