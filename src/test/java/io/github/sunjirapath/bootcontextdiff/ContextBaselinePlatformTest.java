package io.github.sunjirapath.bootcontextdiff;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBaselinePlatformTest {

	@TempDir
	Path dir;

	@Test
	void pathsWithSpacesAndDotDotResolveConsistentlyFromRelativeAndAbsoluteForms() throws Exception {
		Path target = Files.createDirectories(dir.resolve("space dir")).resolve("baseline.json");
		Path mixedSeparators = dir.resolve("discard/../space dir/baseline.json");
		Path windowsSeparators = Path.of(dir + "\\discard\\..\\space dir\\baseline.json");
		try (var context = new GenericApplicationContext()) {
			ContextContract.write(context, mixedSeparators);
			ContextContract.verify(context, target.toAbsolutePath().normalize());
			ContextContract.write(context, windowsSeparators);
			ContextContract.verify(context, target);
			assertThat(Files.isRegularFile(target)).isTrue();
		}
	}

	@Test
	void baselinePathLongerThanTwoHundredCharactersCanBeWrittenAndVerified() throws Exception {
		// Pad relative to the temp dir so the full path is ~210 chars on every OS (Linux temp dirs are short).
		String segment = "p".repeat(Math.max(1, 210 - dir.toString().length() - "/baseline.json".length() - 1));
		Path baseline = dir.resolve(segment).resolve("baseline.json");
		try (var context = new GenericApplicationContext()) {
			ContextContract.write(context, baseline);
			ContextContract.verify(context, baseline);
		}
		assertThat(baseline.toString().length()).isGreaterThan(200);
	}

}
