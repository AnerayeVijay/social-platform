plugins {
    kotlin("jvm") version "2.3.21" apply false
}

allprojects {
    group = "com.example.social"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
