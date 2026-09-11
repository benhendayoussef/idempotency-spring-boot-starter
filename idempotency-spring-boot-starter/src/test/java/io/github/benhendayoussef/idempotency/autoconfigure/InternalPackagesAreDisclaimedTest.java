package io.github.benhendayoussef.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every {@code internal} package must carry a {@code package-info.java} saying it is not supported
 * API.
 *
 * <p>This is the project's only defence against a silent, expensive mistake: a module gets added,
 * its {@code internal} package has public types because auto-configuration in another module has to
 * construct them, and nothing tells a reader those types are off-limits. By the time anyone
 * notices, somebody is depending on them.
 *
 * <p>It happened twice in 0.3 alone - {@code store/caffeine/internal} and {@code webflux/internal}
 * shipped without one. {@code internal/filter} was a third variant of the same mistake: Java does
 * <strong>not</strong> inherit {@code package-info} into subpackages, so the disclaimer on
 * {@code ..idempotency.internal} never covered it.
 *
 * <p>The check reads source rather than classes on purpose. A javadoc-only {@code package-info.java}
 * produces no {@code .class} file at all, so there is nothing to inspect at runtime.
 */
class InternalPackagesAreDisclaimedTest {

    /** Any main-source package named one of these must be disclaimed. */
    private static final List<String> INTERNAL_DIR_NAMES = List.of("internal", "filter", "scope");

    @Test
    void everyInternalMainSourcePackageDeclaresItIsNotSupportedApi() throws IOException {
        Path root = repositoryRoot();
        List<String> undisclaimed = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> internalDirs = paths
                    .filter(Files::isDirectory)
                    .filter(p -> INTERNAL_DIR_NAMES.contains(p.getFileName().toString()))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    // build/ holds copies of the same sources; scanning them double-reports.
                    .filter(p -> !p.toString().replace('\\', '/').contains("/build/"))
                    .toList();

            assertThat(internalDirs)
                    .as("found no internal packages at all - this test is not actually scanning the "
                            + "source tree, so it would pass even if every disclaimer were deleted")
                    .isNotEmpty();

            for (Path dir : internalDirs) {
                Path packageInfo = dir.resolve("package-info.java");
                if (!Files.exists(packageInfo)) {
                    undisclaimed.add(root.relativize(dir) + " (no package-info.java)");
                    continue;
                }
                String text = Files.readString(packageInfo, StandardCharsets.UTF_8);
                if (!text.contains("supported API")) {
                    undisclaimed.add(root.relativize(dir) + " (package-info.java says nothing about "
                            + "supported API)");
                }
            }
        }

        assertThat(undisclaimed)
                .as("these internal packages contain public types with no signal that they are "
                        + "unsupported - add a package-info.java, and remember Java does not inherit "
                        + "one from the parent package")
                .isEmpty();
    }

    /** Walks up from the working directory until it finds the settings file at the repository root. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate).as("could not locate the repository root from the test working directory")
                .isNotNull();
        return candidate;
    }
}
