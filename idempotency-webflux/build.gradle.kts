plugins {
    id("java-library")
}

description = "WebFlux support for @Idempotent: a reactive aspect for Mono-returning handlers."

dependencies {
    api(project(":idempotency-core"))
    api("org.springframework:spring-webflux")
    api("io.projectreactor:reactor-core")
    implementation("org.springframework:spring-web")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.aspectj:aspectjweaver")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("io.projectreactor:reactor-test")
}
