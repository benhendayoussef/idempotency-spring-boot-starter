import org.gradle.plugins.signing.SigningExtension

plugins {
    java
    `maven-publish`
    signing
    alias(libs.plugins.mavenPublish) apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    group = "io.github.benhendayoussef"
    // -Pversion=... overrides this (e.g. for a throwaway smoke release before a real one).
    // providers.gradleProperty(...) is required rather than findProperty("version"): Project's own
    // dynamic-property resolution makes findProperty("version") resolve to Project.getVersion()
    // itself - defaulting to the literal string "unspecified" - rather than null when no -P value
    // was supplied, which silently defeats a naive `findProperty(...) ?: "0.1.0"` fallback.
    version = providers.gradleProperty("version").getOrElse("0.1.0")

    java {
        toolchain { languageVersion = JavaLanguageVersion.of(17) }
    }

    repositories { mavenCentral() }

    dependencies {
        add("implementation", platform(rootProject.libs.spring.boot.dependencies))
        add("annotationProcessor", platform(rootProject.libs.spring.boot.dependencies))
        add("testImplementation", platform(rootProject.libs.spring.boot.dependencies))
        add("testImplementation", platform(rootProject.libs.testcontainers.bom))
        add("testImplementation", rootProject.libs.assertj.core)
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")   // needed for SpEL parameter names
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> { useJUnitPlatform() }

    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    // The demo sample is not published (see samples/sample-orders-api/build.gradle.kts, which
    // disables its jar/publish tasks) - no publication to define for it. "samples" itself is
    // Gradle's implicit parent project for the "samples:sample-orders-api" path (declared in
    // settings.gradle.kts); it has no source of its own and must be excluded too, or it silently
    // gets its own empty, meaningless publication.
    //
    // com.vanniktech.maven.publish targets the Sonatype Central Portal directly and auto-detects
    // the java-library publication along with its sources/javadoc jars. Note that this is why the
    // root `java {}` block above does NOT call withSourcesJar()/withJavadocJar(): the plugin
    // creates its own equivalent tasks, and configuring both fails the build with a
    // duplicate-task-output validation error.
    if (name != "sample-orders-api" && name != "samples") {
        apply(plugin = "com.vanniktech.maven.publish")

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            pom {
                name.set(project.name)
                description.set(project.provider { project.description ?: project.name })
                url.set("https://github.com/benhendayoussef/idempotency-spring-boot-starter")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("benhendayoussef")
                        name.set("benhendayoussef")
                        email.set("benhendayoussef1@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/benhendayoussef/idempotency-spring-boot-starter")
                    connection.set("scm:git:https://github.com/benhendayoussef/idempotency-spring-boot-starter.git")
                    developerConnection.set(
                        "scm:git:ssh://git@github.com/benhendayoussef/idempotency-spring-boot-starter.git")
                }
            }
        }

        // signAllPublications() (above) unconditionally sets signing.isRequired = true, which makes
        // publishToMavenLocal fail outright on a machine with no configured signatory. Override it
        // afterward, gated on whether signing credentials are actually present (CI supplies these
        // as ORG_GRADLE_PROJECT_* environment variables), so local publishToMavenLocal works
        // unsigned while publishing to Central still gets properly signed.
        extensions.configure<SigningExtension> {
            isRequired = project.hasProperty("signingInMemoryKey") || project.hasProperty("signing.keyId")
        }
    }
}
