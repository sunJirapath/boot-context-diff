package io.github.sunjirapath.bootcontextdiff;

import java.nio.file.Files;
import java.nio.file.Path;

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

}
