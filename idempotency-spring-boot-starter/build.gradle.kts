plugins {
    id("java-library")
}

description = "Spring Boot auto-configuration for idempotency-core. Add this dependency to enable @Idempotent."

dependencies {
    api(project(":idempotency-core"))
    compileOnly(project(":idempotency-store-redis"))
    compileOnly(project(":idempotency-store-jdbc"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    compileOnly("org.springframework.data:spring-data-redis")
    compileOnly("org.springframework:spring-jdbc")

    testImplementation(project(":idempotency-store-redis"))
    testImplementation(project(":idempotency-store-jdbc"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    // Spring Boot 4 split @AutoConfigureMockMvc out of spring-boot-test-autoconfigure into its
    // own module; spring-boot-starter-test does not pull it in transitively.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.security:spring-security-test")
    // For a real @EnableWebSecurity filter chain in the Spring Security ordering test, not just
    // the compileOnly-conditional SecurityContextHolder presence the other tests rely on.
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.postgresql:postgresql")
}

// spring-configuration-metadata.json is generated where IdempotencyProperties is actually
// compiled (idempotency-core), not here - see the annotationProcessor comment in its build file.
val coreJarTask = project(":idempotency-core").tasks.named<Jar>("jar")
tasks.test {
    dependsOn(coreJarTask)
    systemProperty("coreJarPath", coreJarTask.get().archiveFile.get().asFile.absolutePath)
    // Virtual threads don't exist on Java 17 (this module's pinned toolchain); this test needs a
    // real embedded server + a JDK 21+ runtime to be meaningful at all, so it runs only via the
    // dedicated testVirtualThreads task below, on its own separate launcher.
    exclude("**/VirtualThreadsTest.class")
}

// Virtual-threads check: runs the same test sources but on a JDK 21+ launcher, without changing
// the project's own Java 17 compile/test toolchain. Pinning that toolchain is about what the
// library is built and normally tested against, not about being unable to verify runtime behaviour
// on a newer JVM a consumer might actually deploy on.
tasks.register<Test>("testVirtualThreads") {
    group = "verification"
    description = "Runs VirtualThreadsTest on a JDK 21+ launcher (virtual threads don't exist on 17)."
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform()
    include("**/VirtualThreadsTest.class")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
