import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    pmd
    id("org.springframework.boot") version "4.1.1"
    id("com.diffplug.spotless") version "8.10.4"
    id("com.github.spotbugs") version "6.5.13"
}

group = "com.ororura"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Spring Boot BOM
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    implementation("com.microsoft.playwright:playwright:1.63.0")
    implementation("org.jsoup:jsoup:1.23.2")

    // Database
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")

    // Spring Framework 7's SpringExtension requires JUnit 6; keep the BOM-managed test stack.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    java {
        googleJavaFormat("1.36.1")
        removeUnusedImports()
    }
    format("projectFiles") {
        target(
            "*.kts",
            "*.properties",
            ".gitignore",
            ".editorconfig",
            ".gitattributes",
            "README.md",
            "src/**/*.yml",
            "src/**/*.sql",
            ".github/**/*.yml"
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

pmd {
    toolVersion = "7.28.0"
    isConsoleOutput = true
    isIgnoreFailures = false
    ruleSets = listOf(
        "category/java/errorprone.xml"
    )
}

spotbugs {
    toolVersion = "4.10.5"
    ignoreFailures = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Pmd>().configureEach {
    classpath = files(
        classpath,
        javaToolchains.launcherFor(java.toolchain).map {
            it.metadata.installationPath.file("lib/jrt-fs.jar")
        }
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("playwrightInstall") {
    group = "application"
    description = "Install Chromium binaries for this Playwright version"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}

tasks.bootRun {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
