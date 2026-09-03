import java.security.MessageDigest
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("org.jetbrains.intellij.platform") version "2.18.1" apply false
}

allprojects {
    group = "com.ilkimgul.vpsgraph"
    version = "0.1.1"
}

val packageServerHelper by tasks.registering(Tar::class) {
    archiveBaseName.set("vps-graph-server-helper")
    archiveVersion.set(project.version.toString())
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("server-helper") {
        include("vpsgraph_docker_scan.py", "install.sh", "uninstall.sh", "README.md")
        into("vps-graph-server-helper-${project.version}")
    }
}

val checksumServerHelper by tasks.registering {
    dependsOn(packageServerHelper)
    val archive = packageServerHelper.flatMap { it.archiveFile }
    val checksum = layout.buildDirectory.file("distributions/vps-graph-server-helper-${project.version}.tar.gz.sha256")
    inputs.file(archive)
    outputs.file(checksum)
    doLast {
        val file = archive.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        checksum.get().asFile.writeText("$digest  ${file.name}\n")
    }
}

tasks.register("releaseArtifacts") {
    dependsOn(":intellij-plugin:buildPlugin", checksumServerHelper)
}
