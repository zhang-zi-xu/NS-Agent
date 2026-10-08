package com.nongxin.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Source-level guardrails without adding a framework or coupling tests to private method layouts.
 */
class ArchitectureTest {
    private static final Path JAVA = Path.of("src/main/java");
    private static final Path ROOT = JAVA.resolve("com/nongxin");
    private static final Pattern SQL =
            Pattern.compile(
                    "\"\\s*(?:SELECT\\s|INSERT\\s|UPDATE\\s|DELETE\\s|REPLACE\\s|CREATE\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE|PRAGMA\\s)");

    @Test
    void packagesMatchTheirDirectories() throws IOException {
        for (Path file : sources(ROOT)) {
            var declaration =
                    Pattern.compile("(?m)^package ([\\w.]+);").matcher(Files.readString(file));
            assertThat(declaration.find()).as("package declaration: %s", file).isTrue();
            assertThat(JAVA.relativize(file.getParent()).toString().replace('\\', '/'))
                    .as("package location: %s", file)
                    .isEqualTo(declaration.group(1).replace('.', '/'));
        }
    }

    @Test
    void businessAndPersistenceNeverDependOnControllersOrHttpResponses() throws IOException {
        for (String layer : List.of("service", "agent", "repository")) {
            for (Path file : sources(ROOT.resolve(layer))) {
                assertThat(Files.readString(file))
                        .as("layer boundary: %s", file)
                        .doesNotContain(
                                "com.nongxin.controller.",
                                "ResponseEntity",
                                "SseEmitter",
                                "jakarta.servlet.",
                                "org.springframework.web.bind.",
                                "org.springframework.web.context.",
                                "org.springframework.web.servlet.");
            }
        }
        for (Path file : sources(ROOT.resolve("domain"))) {
            assertThat(Files.readString(file))
                    .as("domain boundary: %s", file)
                    .doesNotContain(
                            "ResponseEntity", "org.springframework.http.", "jakarta.servlet.");
        }
    }

    @Test
    void sqlAndJdbcAccessStayInPersistenceAndDatabaseBootstrap() throws IOException {
        for (Path file : sources(ROOT)) {
            String relative = ROOT.relativize(file).toString().replace('\\', '/');
            if (relative.startsWith("repository/") || relative.startsWith("bootstrap/")) continue;
            String source = Files.readString(file);
            assertThat(SQL.matcher(source).find())
                    .as("SQL outside repository/bootstrap: %s", file)
                    .isFalse();
            assertThat(source)
                    .as("JDBC access outside persistence/bootstrap: %s", file)
                    .doesNotContain("org.springframework.jdbc.core.");
        }
    }

    @Test
    void serviceAndRepositoryRootsDeclareInterfacesNotImplementations() throws IOException {
        for (String layer : List.of("service", "repository")) {
            try (var files = Files.list(ROOT.resolve(layer))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    assertThat(Files.readString(file))
                            .as("public contract: %s", file)
                            .contains("public interface ")
                            .doesNotContain("@Service", "@Repository");
                }
            }
        }
    }

    private static List<Path> sources(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
        }
    }
}
