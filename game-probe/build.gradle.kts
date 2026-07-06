plugins {
    id("java")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

dependencies {
    implementation(libs.ow2.asm)
    implementation(libs.ow2.asm.tree)
    testImplementation(libs.ow2.asm.util)
    testImplementation(libs.junit4)
}

tasks.register<Jar>("fatJar") {
    archiveFileName = "game-probe.jar"
    manifest {
        attributes(
            "Premain-Class" to "io.stamethyst.agent.GameProbe",
            "Agent-Class" to "io.stamethyst.agent.GameProbe",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().filter { f ->
            f.name.contains("asm-")
        }.map { f ->
            if (f.isDirectory) f else zipTree(f)
        }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.jar {
    dependsOn(tasks.named("fatJar"))
    enabled = false
}
tasks.named("assemble") {
    dependsOn(tasks.named("fatJar"))
}
