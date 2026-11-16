package io.github.sunjirapath.bootcontextdiff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFormatRobustnessTest {

	@TempDir
	Path dir;

	@Test
	void baselineFileEncodingRemainsUtf8() throws Exception {
		Path baseline = dir.resolve("unicode.json");
		try (var context = new GenericApplicationContext()) {
			context.registerBeanDefinition("บริการ-😀", new RootBeanDefinition(String.class));
			ContextContract.write(context, baseline);
			ContextContract.verify(context, baseline);
		}
		assertThat(new String(Files.readAllBytes(baseline), StandardCharsets.UTF_8)).contains("บริการ-😀");
	}

}
