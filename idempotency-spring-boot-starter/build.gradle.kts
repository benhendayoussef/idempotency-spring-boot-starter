plugins {
    id("java-library")
}

description = "Spring Boot auto-configuration for idempotency-core. Add this dependency to enable @Idempotent."

dependencies {
    api(project(":idempotency-core"))
    compileOnly(project(":idempotency-store-redis"))
    compileOnly(project(":idempotency-store-jdbc"))
    compileOnly(project(":idempotency-store-caffeine"))
    compileOnly(project(":idempotency-webflux"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    compileOnly("org.springframework.data:spring-data-redis")
    compileOnly("org.springframework:spring-jdbc")
    // Optional: metrics are wired only when the application already has a MeterRegistry.
    compileOnly("io.micrometer:micrometer-core")
    // Optional: tracing is wired only when the application already has a Tracer.
    compileOnly("io.micrometer:micrometer-tracing")
    // For HandlerMapping, referenced when wiring the filter-mode bean. Servlet MVC is
    // always present at runtime under @ConditionalOnWebApplication(SERVLET), so compileOnly.
    compileOnly("org.springframework:spring-webmvc")
    // Optional: the management endpoint is registered only when actuator is present.
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")

    testImplementation(project(":idempotency-store-redis"))
    testImplementation(project(":idempotency-store-jdbc"))
    testImplementation("io.micrometer:micrometer-core")
    testImplementation("io.micrometer:micrometer-tracing")
    // SimpleTracer records spans and their tags in memory - a real Tracer implementation with no
    // exporter, collector or OTel/Brave bridge to stand up just to assert one tag.
    testImplementation("io.micrometer:micrometer-tracing-test")
    testImplementation(project(":idempotency-store-caffeine"))
    testImplementation(project(":idempotency-webflux"))
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    // No spring-boot-webmvc-test here on purpose: that module is Boot 4 only, and depending on it
    // is what used to pin this suite to a single Boot generation. MockMvcTestConfiguration builds
    // MockMvc from spring-test instead, which is identical on Boot 3 and 4.
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
    testImplementation("org.testcontainers:mysql")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.postgresql:postgresql")
}

// spring-configuration-metadata.json is generated where IdempotencyProperties is actually
// compiled (idempotency-core), not here - see the annotationProcessor comment in its build file.
val coreJarTask = project(":idempotency-core").tasks.named<Jar>("jar")
tasks.test {
    dependsOn(coreJarTask)

    // InternalPackagesAreDisclaimedTest reads the source tree rather than the classpath, because a
    // javadoc-only package-info.java produces no .class file. That same fact means Gradle sees no
    // input change when one is added or deleted, so without declaring the sources explicitly the
    // test is skipped as UP-TO-DATE in exactly the case it exists to catch. Verified: removing a
    // package-info and running `gradlew test` passed until this was added.
    inputs.files(
        rootProject.subprojects.mapNotNull { sub ->
            sub.layout.projectDirectory.dir("src/main/java").asFile.takeIf { it.exists() }
        }
    ).withPropertyName("mainSourcesForPackageInfoScan").withPathSensitivity(PathSensitivity.RELATIVE)
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
