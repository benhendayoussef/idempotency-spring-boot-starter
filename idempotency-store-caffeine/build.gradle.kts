plugins {
    id("java-library")
}

description = "Caffeine-backed idempotency store: single-instance, with real eviction and a bounded footprint."

dependencies {
    api(project(":idempotency-core"))
    api("com.github.ben-manes.caffeine:caffeine")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
