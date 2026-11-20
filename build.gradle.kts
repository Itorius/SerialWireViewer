import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // Cross-platform serial port access (bundled into the plugin's lib folder)
    implementation(libs.jserialcomm)

    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // The plugin only depends on com.intellij.modules.platform, so it runs in CLion as well.
        // To launch CLion via `runIde` instead of IDEA, replace this with e.g. clion("2026.2.x").
        intellijIdea("2026.2.4")
        testFramework(TestFrameworkType.Platform)
    }
}
