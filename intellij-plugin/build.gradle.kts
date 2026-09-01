import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":scanner-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaCommunity("2025.1.7.2")
    }
}

tasks.test {
    useJUnitPlatform()
}

val frontendDirectory = rootProject.layout.projectDirectory.dir("graph-ui")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

val installFrontend by tasks.registering(Exec::class) {
    workingDir(frontendDirectory)
    commandLine(npmCommand, "ci")
    inputs.file(frontendDirectory.file("package.json"))
    inputs.file(frontendDirectory.file("package-lock.json"))
    outputs.dir(frontendDirectory.dir("node_modules"))
}

val buildFrontend by tasks.registering(Exec::class) {
    dependsOn(installFrontend)
    workingDir(frontendDirectory)
    commandLine(npmCommand, "run", "build")
    inputs.dir(frontendDirectory.dir("src"))
    inputs.file(frontendDirectory.file("index.html"))
    inputs.file(frontendDirectory.file("vite.config.ts"))
    outputs.dir(frontendDirectory.dir("dist"))
}

tasks.named<Copy>("processResources") {
    dependsOn(buildFrontend)
    from(frontendDirectory.dir("dist")) {
        into("web")
    }
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
    }
    from(frontendDirectory.dir("node_modules")) {
        include("**/LICENSE", "**/LICENSE.*", "**/COPYING", "**/COPYING.*")
        includeEmptyDirs = false
        into("META-INF/licenses/npm")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "251.*"
        }
    }

    pluginVerification {
        ides {
            current()
        }
    }
}
