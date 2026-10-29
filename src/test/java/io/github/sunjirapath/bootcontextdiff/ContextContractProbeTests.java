package io.github.sunjirapath.bootcontextdiff;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Input-matrix probes through the public API: a raw exception where a clear message belongs is a bug. */
class ContextContractProbeTests {

	@TempDir
	Path dir;

	private final ApplicationContextRunner runner = new ApplicationContextRunner();

	@Test
	void realAutoConfigurationsRoundTripAndAreDeterministic() throws Exception {
		ApplicationContextRunner boot = runner.withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
				MessageSourceAutoConfiguration.class, TaskExecutionAutoConfiguration.class));
		Path a = dir.resolve("a.json");
		Path b = dir.resolve("b.json");
		boot.run((ctx) -> ContextContract.write(ctx, a));
		boot.run((ctx) -> {
			ContextContract.write(ctx, b);
			assertThatCode(() -> ContextContract.verify(ctx, a)).doesNotThrowAnyException();
		});
		assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
		assertThat(Files.readString(a)).doesNotContain("$$").doesNotContainPattern("\\$Proxy\\d")
			.doesNotContain("0x");
	}

	@Test
	void wrongSchemaVersionNamesTheFile() throws Exception {
		Path baseline = dir.resolve("v2.json");
		Files.writeString(baseline, "{\"schemaVersion\":2,\"beans\":{},\"conditions\":{}}");
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, baseline))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(baseline.toString())
			.hasMessageContaining("schemaVersion"));
	}

	@Test
	void unknownFieldsInBaselineAreNotARawFailure() throws Exception {
		Path baseline = dir.resolve("extra.json");
		Files.writeString(baseline, "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},\"note\":\"hand edited\"}");
		runner.run((ctx) -> {
			ContextContract.write(ctx, dir.resolve("real.json"));
			assertThatCode(() -> ContextContract.verify(ctx, baseline)).satisfiesAnyOf(
					(ex) -> assertThat(ex).isNull(),
					(ex) -> assertThat(ex).hasMessageContaining(baseline.toString()));
		});
	}

	@Test
	void nullEntriesInBaselineDoNotCauseNpe() throws Exception {
		for (String content : new String[] { "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":null}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":null}}}",
				"{\"schemaVersion\":1,\"beans\":{\"b\":null},\"conditions\":{}}" }) {
			Path baseline = dir.resolve("nulls.json");
			Files.writeString(baseline, content);
			runner.run((ctx) -> {
				BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(String.class);
				((BeanDefinitionRegistry) ctx.getBeanFactory()).registerBeanDefinition("b", builder.getBeanDefinition());
				assertThatThrownBy(() -> ContextContract.verify(ctx, baseline)).as("baseline content: %s", content)
					.isNotInstanceOf(NullPointerException.class);
			});
		}
	}

	@Test
	void directoryAsBaselineGetsAnAccurateMessage() throws Exception {
		Path directory = Files.createDirectory(dir.resolve("a-dir"));
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, directory))
			.hasMessageContaining(directory.toString())
			.hasMessageNotContaining("No context baseline"));
	}

	@Test
	void writeIntoUnwritableLocationNamesThePath() throws Exception {
		Path file = Files.writeString(dir.resolve("plain-file"), "x");
		Path under = file.resolve("child.json");
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.write(ctx, under))
			.isInstanceOf(java.io.UncheckedIOException.class)
			.hasMessageContaining("child.json"));
	}

	@Test
	void abstractDefinitionsAreNotListed() {
		runner.run((ctx) -> {
			BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(String.class).setAbstract(true);
			((BeanDefinitionRegistry) ctx.getBeanFactory()).registerBeanDefinition("template", builder.getBeanDefinition());
			assertThat(ContextSnapshot.of(ctx).beans()).doesNotContainKey("template");
		});
	}

	@Test
	void nestedWriteCreatesMissingParents() {
		Path nested = dir.resolve("a/b/c/baseline.json");
		runner.run((ctx) -> {
			ContextContract.write(ctx, nested);
			ContextContract.verify(ctx, nested);
		});
	}

}
