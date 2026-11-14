package io.github.sunjirapath.bootcontextdiff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstallationDocumentationTests {

	@Test
	void consumerCiDocumentsPinnedSourceAcquisitionBeforeInstallAndVerification() throws Exception {
		String readme = Files.readString(Path.of("README.md"));
		int start = readme.indexOf("name: Consumer CI");
		assertThat(start).isGreaterThanOrEqualTo(0);
		String workflow = readme.substring(start, readme.indexOf("```", start));
		assertThat(workflow).contains("LIBRARY_REV: ${{ vars.BOOT_CONTEXT_DIFF_REV }}",
				"[[ \"$LIBRARY_REV\" =~ ^[0-9a-f]{40}$ ]] ||",
				"exit 1;", "repository: sunJirapath/boot-context-diff",
				"ref: ${{ vars.BOOT_CONTEXT_DIFF_REV }}", "path: .context-contract-library");
		int acquisition = workflow.indexOf("repository: sunJirapath/boot-context-diff");
		int install = workflow.indexOf("./.context-contract-library/mvnw -B -f .context-contract-library/pom.xml install");
		int verify = workflow.indexOf("./mvnw -B verify");
		assertThat(acquisition).isGreaterThan(workflow.indexOf("exit 1;"));
		assertThat(install).isGreaterThan(acquisition);
		assertThat(verify).isGreaterThan(install);
	}

	@Test
	void gradleExamplesResolveTheLibraryExclusivelyFromTheRequiredRepository() throws Exception {
		for (String script : List.of("examples/gradle-groovy/build.gradle", "examples/gradle-kotlin/build.gradle.kts")) {
			String build = Files.readString(Path.of(script)).replace('\'', '"');
			assertThat(build).as(script)
				.contains("exclusiveContent {", "forRepository {",
						"url = uri(providers.gradleProperty(\"contextContractRepository\").get())",
						"includeGroup(\"io.github.sunjirapath.bootcontextdiff\")")
				.doesNotContain("mavenLocal()");
		}
	}

	@Test
	void consumerAndProjectCiUseTheSameIsolatedRepositoryThroughout() throws Exception {
		String readme = Files.readString(Path.of("README.md"));
		int start = readme.indexOf("name: Consumer CI");
		assertThat(start).isGreaterThanOrEqualTo(0);
		String consumer = readme.substring(start, readme.indexOf("```", start));
		String project = Files.readString(Path.of(".github/workflows/ci.yml"));
		for (String workflow : List.of(consumer, project)) {
			assertThat(workflow).contains("CONTEXT_CONTRACT_REPOSITORY=$RUNNER_TEMP/context-contract-m2/",
					">> \"$GITHUB_ENV\"");
			assertThat(workflow.lines().filter((line) -> line.contains("run:") && line.contains("mvnw")).toList())
				.hasSize(2)
				.allSatisfy((line) -> assertThat(line).contains("-Dmaven.repo.local=\"$CONTEXT_CONTRACT_REPOSITORY\""));
		}
		assertThat(project.lines().filter((line) -> line.contains("run:") && line.contains("gradlew")).toList())
			.hasSize(2)
			.allSatisfy((line) -> assertThat(line).contains("-PcontextContractRepository=\"$CONTEXT_CONTRACT_REPOSITORY\""));
		assertThat(readme).contains("same approved full commit SHA", "does not attest provenance",
				"do not restore a cached source-built artifact");
	}

	@Test
	void baselineRegenerationCommandsUseEachExamplesOwnWrapper() throws Exception {
		String readme = Files.readString(Path.of("README.md"));
		Map.of("maven", "mvnw", "gradle-groovy", "gradlew", "gradle-kotlin", "gradlew")
			.forEach((example, wrapper) -> {
				assertThat(Path.of("examples", example, wrapper)).isRegularFile();
				assertThat(readme).contains("(cd examples/" + example
						+ " && UPDATE_CONTEXT_BASELINE=true ./" + wrapper + " test ");
			});
		assertThat(readme).doesNotContain("in any one example");
	}

	@Test
	void compatibilityDocumentsJacksonClasspathInfluence() throws Exception {
		assertThat(Files.readString(Path.of("README.md"))).contains("Test-classpath influence",
				"required transitive dependency", "not verified production-classpath parity",
				"A Jackson-2-only production context is not", "coverage as incomplete");
	}

	@Test
	void privacyDocumentationWarnsAboutSensitiveIdentifiers() throws Exception {
		assertThat(Files.readString(Path.of("README.md")))
			.contains("Identifiers are not secret-redacted", "property-derived values",
					"Sensitive identifiers can appear in both baseline JSON", "and failure messages",
					"Callers must use non-sensitive identifiers")
			.doesNotContain("never serializes property values");
	}

}
