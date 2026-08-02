plugins {
    id("java-library")
}

description = "Redis implementation of the idempotency store SPI."

dependencies {
    api(project(":idempotency-core"))
    api("org.springframework.data:spring-data-redis")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("io.lettuce:lettuce-core")
}
