plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnit()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
