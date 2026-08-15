plugins {
    id("java-library")
}

description = "Postgres/JDBC implementation of the idempotency store SPI, with opt-in exactly-once via transaction joining."

dependencies {
    api(project(":idempotency-core"))
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-tx")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.postgresql:postgresql")
}
