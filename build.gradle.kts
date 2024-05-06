plugins {
    kotlin("jvm") version "1.9.22"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.mashape.unirest:unirest-java:1.4.9")
    implementation("org.json:json:20240303")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
// https://mvnrepository.com/artifact/org.eclipse.jgit/org.eclipse.jgit
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r")

}

tasks.create<JavaExec>("downloadPlugins") {
    group = "plugin-hub"
    classpath = sourceSets.main.get().runtimeClasspath
    description = "Download plugins from official RuneLite plugin hub"
    mainClass.set("PluginDownloader")
}

tasks.create<JavaExec>("verifyKeys") {
    group = "plugin-hub"

    classpath = sourceSets.main.get().runtimeClasspath
    description = "Validate public and private key for your RuneLite plugin hub"
    mainClass.set("KeyValidator")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
}