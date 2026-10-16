// Skirmish — standard gradle build. This is the one to use.
//
// `gradlew build` if you have the wrapper, or `gradle build` with gradle 8+.
// The jar lands in build/libs/.
plugins { id("java") }

group = "app.vexoris"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Compiled against 1.21.4, and deliberately written to run on anything from 1.21 to
    // current Paper: the one call that differs between those eras (the teleport cause for
    // chorus fruit) is matched by NAME rather than by enum constant. See TeleportGateListener.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.test { useJUnitPlatform() }

tasks.jar {
    archiveBaseName.set("Skirmish")
    from(sourceSets.main.get().resources)
}
