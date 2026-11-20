plugins {
    kotlin("multiplatform") version "2.4.21"
    `maven-publish`
}

group = "com.github.buxerr"
// JitPack sets VERSION to the Git tag being built (for example, v0.1.0).
version = providers.environmentVariable("VERSION").orElse("0.1.0").get()

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    linuxX64()

}

val jvmTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")

// The test source is a dependency-free executable harness, run by simulatorChecks below.
tasks.named("jvmTest") {
    enabled = false
}

tasks.register<JavaExec>("simulatorChecks") {
    group = "verification"
    description = "Runs deterministic sync fault and adapter conformance scenarios."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    mainClass.set("dev.buxerr.syncsim.SyncSimulatorTest")
}

tasks.check {
    dependsOn("simulatorChecks")
}
