// ─────────────────────────────────────────────────────────────────────────────
// NexusBedwars – Gradle build (Kotlin DSL)
//
// Design decisions
//  * Pure Paper API (no NMS / no paperweight): every API break between 1.21 and
//    26.x is isolated in `platform/Compat.java` (registry-based lookups).
//  * Runtime libraries (HikariCP, JDBC drivers, Jedis) are NOT shaded. They are
//    resolved by Paper's library loader (see BedwarsLoader) → tiny jar, no
//    relocation headaches, drivers stay upgradeable.
//  * Bytecode target is Java 21; it runs unchanged on newer JVMs (Java 25 for
//    the 26.x server line).
// ─────────────────────────────────────────────────────────────────────────────
plugins {
    java
}

group = "dev.nexus"
version = "1.0.0"
description = "Enterprise-grade Bedwars for Paper / Purpur / Folia"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
}

dependencies {
    // Platform – compile against the oldest supported API, run on anything newer.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Soft dependencies
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }

    // Runtime libraries – downloaded at boot by BedwarsLoader (paper-plugin library loader).
    // Keep these coordinates in sync with BedwarsLoader.LIBRARIES.
    compileOnly("com.zaxxer:HikariCP:6.2.1")
    compileOnly("org.xerial:sqlite-jdbc:3.47.1.0")
    compileOnly("com.mysql:mysql-connector-j:9.1.0")
    compileOnly("org.postgresql:postgresql:42.7.4")
    compileOnly("redis.clients:jedis:5.2.0")
}

tasks {
    compileJava {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
    }
    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") { expand(props) }
    }
    jar {
        archiveFileName.set("NexusBedwars-${project.version}.jar")
    }
}
