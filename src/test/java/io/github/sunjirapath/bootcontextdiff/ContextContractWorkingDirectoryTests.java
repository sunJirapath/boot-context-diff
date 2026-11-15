package io.github.sunjirapath.bootcontextdiff;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ContextContractWorkingDirectoryTests {

	@TempDir
	Path dir;

	@Test
	void absoluteModuleBaselinesKeepOwnershipFromModuleRootAndIdeWorkingDirectories() throws Exception {
		Path repository = Files.createDirectory(dir.resolve("repository"));
		Path ide = Files.createDirectory(repository.resolve("ide-working-directory"));
		for (String moduleName : List.of("module-a", "module-b")) {
			Path module = Files.createDirectory(repository.resolve(moduleName));
			Path baseline = module.resolve("src/test/resources/context-baseline.json").toAbsolutePath();
			runFrom(module, "write", baseline, moduleName);
			byte[] before = Files.readAllBytes(baseline);
			for (Path workingDirectory : List.of(module, repository, ide)) {
				runFrom(workingDirectory, "verify", baseline, moduleName);
			}
			// Deliberate regeneration from an IDE must still update only this module's file.
			runFrom(ide, "write", baseline, moduleName);
			assertThat(Files.readAllBytes(baseline)).isEqualTo(before);
			assertThat(Files.readString(baseline)).contains("owner-" + moduleName);
		}
		assertThat(repository.resolve("src/test/resources/context-baseline.json")).doesNotExist();
		assertThat(ide.resolve("src/test/resources/context-baseline.json")).doesNotExist();
		assertThat(Files.readString(repository.resolve("module-a/src/test/resources/context-baseline.json")))
			.doesNotContain("owner-module-b");
	}

	private void runFrom(Path workingDirectory, String action, Path baseline, String owner) throws Exception {
		String classpath = Arrays.stream(System.getProperty("surefire.test.class.path",
				System.getProperty("java.class.path")).split(File.pathSeparator))
			.map((entry) -> Path.of(entry).toAbsolutePath().toString())
			.collect(Collectors.joining(File.pathSeparator));
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		Process process = new ProcessBuilder(java.toString(), "-cp", classpath,
				WorkingDirectoryProbe.class.getName(), action, baseline.toString(), owner)
			.directory(workingDirectory.toFile()).redirectErrorStream(true).start();
		try {
			assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("child JVM completed").isTrue();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(process.exitValue()).as("child output: %s", output).isZero();
		}
		finally {
			process.destroyForcibly();
		}
	}

	public static class WorkingDirectoryProbe {

		public static void main(String[] args) {
			try (var context = new GenericApplicationContext()) {
				context.registerBean("owner-" + args[2], String.class, () -> "value");
				context.refresh();
				Path baseline = Path.of(args[1]);
				if ("write".equals(args[0])) {
					ContextContract.write(context, baseline);
				}
				else {
					ContextContract.verify(context, baseline);
				}
			}
		}

	}

}
