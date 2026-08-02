plugins {
    id("java-library")
}

description = "Annotation, SPI, aspect and hashing for idempotent request replay."

dependencies {
    api("org.springframework:spring-context")
    api("org.aspectj:aspectjweaver")
    api("org.springframework.boot:spring-boot")
    api("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-webmvc")
    implementation("jakarta.servlet:jakarta.servlet-api")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")

    // Optional integrations for the built-in scope resolvers. Guarded by
    // @ConditionalOnClass at the auto-configuration layer; not required at runtime.
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.security:spring-security-oauth2-jose")

    // IdempotencyProperties (the @ConfigurationProperties class) lives in this module - the
    // annotation processor only sees source compiled in its own module, so it must be declared
    // here, not in the starter, or spring-configuration-metadata.json is never generated at all.
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.security:spring-security-oauth2-jose")
}

// The configuration-processor merges META-INF/additional-spring-configuration-metadata.json from
// the resources output dir at annotation-processing time. compileJava and processResources have
// no inherent ordering, so without this, hints/descriptions from the additional file silently
// don't merge whenever compileJava happens to run first.
tasks.compileJava {
    dependsOn(tasks.processResources)
}
