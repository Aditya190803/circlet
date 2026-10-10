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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notice"))
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
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7")

    testImplementation(libs.junit)
}
