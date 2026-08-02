plugins {
    id("java-library")
    alias(libs.plugins.springBoot)
}

description = "Runnable demo of the idempotency starter. Not published."

// This module is a demo, not a published artifact. sourcesJar/javadocJar don't exist here at all
// (com.vanniktech.maven.publish creates those, and it is only applied to the four library modules),
// so only the plain "jar" from java-library and the "publish"/"publishToMavenLocal" lifecycle tasks
// from the root build's unconditionally-applied maven-publish plugin need disabling.
tasks.named<Jar>("jar") { enabled = false }
tasks.named("publish") { enabled = false }
tasks.named("publishToMavenLocal") { enabled = false }

dependencies {
    implementation(project(":idempotency-spring-boot-starter"))
    implementation(project(":idempotency-store-redis"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}
