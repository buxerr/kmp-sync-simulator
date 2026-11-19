plugins {
    kotlin("multiplatform") version "2.4.21"
}

kotlin {
    jvm()
    linuxX64()

}

val jvmTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")

tasks.register<JavaExec>("simulatorChecks") {
    group = "verification"
    description = "Runs deterministic sync fault and adapter conformance scenarios."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    mainClass.set("dev.buxerr.syncsim.SyncSimulatorTest")
}

tasks.check {
    dependsOn("simulatorChecks")
}
