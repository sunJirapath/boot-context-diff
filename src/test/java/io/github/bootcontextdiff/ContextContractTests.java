package io.github.bootcontextdiff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextContractTests {

	@TempDir
	Path dir;

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(Auto.class));

	@Test
	void identicalContextHasNoDiff() {
		Path baseline = dir.resolve("baseline.json");
		runner.run((ctx) -> ContextContract.write(ctx, baseline));
		runner.run((ctx) -> assertThatCode(() -> ContextContract.verify(ctx, baseline)).doesNotThrowAnyException());
	}

	@Test
	void addedBeanIsReportedAndBaselineIsKept() throws Exception {
		Path baseline = dir.resolve("baseline.json");
		runner.run((ctx) -> ContextContract.write(ctx, baseline));
		String before = Files.readString(baseline);
		runner.withBean("extra", String.class, () -> "x").run((ctx) -> assertThatThrownBy(
				() -> ContextContract.verify(ctx, baseline))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("ADDED BEAN        extra (java.lang.String)")
			.hasMessageContaining(baseline.toAbsolutePath().toString())
			.hasMessageContaining("not necessarily a bug"));
		assertThat(Files.readString(baseline)).isEqualTo(before);
	}

	@Test
	void removedBeanIsReported() {
		Path baseline = dir.resolve("baseline.json");
		runner.withBean("extra", String.class, () -> "x").run((ctx) -> ContextContract.write(ctx, baseline));
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, baseline))
			.hasMessageContaining("REMOVED BEAN      extra (was java.lang.String)"));
	}

	@Test
	void changedBeanTypeIsReported() {
		Path baseline = dir.resolve("baseline.json");
		runner.withBean("things", ArrayList.class, ArrayList::new).run((ctx) -> ContextContract.write(ctx, baseline));
		runner.withBean("things", LinkedList.class, LinkedList::new).run((ctx) -> assertThatThrownBy(
				() -> ContextContract.verify(ctx, baseline))
			.hasMessageContaining("BEAN TYPE CHANGED things: java.util.ArrayList -> java.util.LinkedList"));
	}

	@Test
	void changedConditionOutcomeIsReported() {
		Path baseline = dir.resolve("baseline.json");
		runner.withPropertyValues("demo.feature=on").run((ctx) -> ContextContract.write(ctx, baseline));
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, baseline))
			.hasMessageContaining("CONDITION CHANGED")
			.hasMessageContaining("[OnPropertyCondition]: matched -> not matched")
			.hasMessageContaining("REMOVED BEAN      feature"));
	}

	@Test
	void outputIsDeterministicAndSorted() throws Exception {
		Path a = dir.resolve("a.json");
		Path b = dir.resolve("b.json");
		runner.withBean("zeta", String.class, () -> "z").withBean("alpha", String.class, () -> "a")
			.run((ctx) -> ContextContract.write(ctx, a));
		runner.withBean("alpha", String.class, () -> "a").withBean("zeta", String.class, () -> "z")
			.run((ctx) -> ContextContract.write(ctx, b));
		String json = Files.readString(a);
		assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
		assertThat(json).doesNotContain("\r").startsWith("{\n  \"schemaVersion\" : 1,");
		assertThat(json.indexOf("\"alpha\"")).isLessThan(json.indexOf("\"zeta\""));
	}

	@Test
	void noPropertyValuesAreSerialized() throws Exception {
		Path baseline = dir.resolve("baseline.json");
		// the secret is the value the condition actually evaluates
		runner.withPropertyValues("demo.feature=hunter2").run((ctx) -> ContextContract.write(ctx, baseline));
		assertThat(Files.readString(baseline)).doesNotContain("hunter2").contains("OnPropertyCondition");
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, baseline))
			.hasMessageNotContaining("hunter2"));
	}

	@Test
	void proxiesAndGeneratedClassesDoNotCauseNoise() throws Exception {
		Path a = dir.resolve("a.json");
		Path b = dir.resolve("b.json");
		ApplicationContextRunner proxied = runner.withUserConfiguration(Proxying.class);
		proxied.run((ctx) -> {
			assertThat(ctx.getBean("job").getClass().getName()).contains("Proxy");
			ContextContract.write(ctx, a);
		});
		proxied.run((ctx) -> ContextContract.write(ctx, b));
		String json = Files.readString(a);
		assertThat(json).doesNotContainPattern("\\$Proxy\\d").doesNotContain("$$").contains("\"job\" : \"java.lang.Runnable\"");
		assertThat(json).isEqualTo(Files.readString(b));
	}

	@Test
	void missingBaselineFailsWithInstruction() {
		Path missing = dir.resolve("nope.json");
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, missing))
			.hasMessageContaining(missing.toString())
			.hasMessageContaining("ContextContract.write"));
		assertThat(missing).doesNotExist();
	}

	@Test
	void unreadableBaselineFailsNamingTheFile() throws Exception {
		for (String content : new String[] { "", "{", "not json", "[]", "null" }) {
			Path baseline = dir.resolve("bad.json");
			Files.writeString(baseline, content);
			runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, baseline))
				.as("baseline content: %s", content)
				.hasMessageContaining(baseline.toString()));
		}
	}

	@Test
	void baselineMissingSectionsFailsClearlyInsteadOfNpe() throws Exception {
		for (String content : new String[] { "{\"schemaVersion\":1}", "{\"schemaVersion\":1,\"beans\":{}}",
				"{\"schemaVersion\":1,\"conditions\":{}}", "{\"schemaVersion\":1,\"beans\":null,\"conditions\":null}" }) {
			Path baseline = dir.resolve("partial.json");
			Files.writeString(baseline, content);
			runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, baseline))
				.as("baseline content: %s", content)
				.isNotInstanceOf(NullPointerException.class)
				.hasMessageContaining(baseline.toString()));
		}
	}

	@Test
	void crlfBaselineAndUnusualBeanNamesRoundTrip() throws Exception {
		Path baseline = dir.resolve("baseline.json");
		ApplicationContextRunner odd = runner.withBean("we\"ird\\nameé中", String.class, () -> "x");
		odd.run((ctx) -> ContextContract.write(ctx, baseline));
		Files.writeString(baseline, Files.readString(baseline).replace("\n", "\r\n"));
		odd.run((ctx) -> assertThatCode(() -> ContextContract.verify(ctx, baseline)).doesNotThrowAnyException());
	}

	@Test
	void byteOrderMarkInBaselineIsTolerated() {
		Path baseline = dir.resolve("baseline.json");
		runner.run((ctx) -> {
			ContextContract.write(ctx, baseline);
			Files.writeString(baseline, "﻿" + Files.readString(baseline));
			assertThatCode(() -> ContextContract.verify(ctx, baseline)).doesNotThrowAnyException();
		});
	}

	@Test
	void unresolvableBeanClassDoesNotCrashTheSnapshot() {
		Path baseline = dir.resolve("baseline.json");
		runner.run((ctx) -> {
			GenericBeanDefinition missing = new GenericBeanDefinition();
			missing.setBeanClassName("does.not.Exist");
			missing.setLazyInit(true);
			// registered after refresh: a class that cannot load at startup would fail the context itself
			((BeanDefinitionRegistry) ctx.getBeanFactory()).registerBeanDefinition("missing", missing);
			ContextContract.write(ctx, baseline);
			assertThat(Files.readString(baseline)).contains("\"missing\" : \"?\"");
		});
	}

	@Test
	void relativeBaselinePathWithoutParentWorks() throws Exception {
		Path relative = Path.of("bcd-relative-" + System.nanoTime() + ".json");
		try {
			runner.run((ctx) -> {
				ContextContract.write(ctx, relative);
				ContextContract.verify(ctx, relative);
			});
		}
		finally {
			Files.deleteIfExists(relative);
		}
	}

	@Configuration
	static class Auto {

		@Bean
		String greeting() {
			return "hi";
		}

		@Bean
		@ConditionalOnProperty("demo.feature")
		Object feature() {
			return new Object();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class Proxying {

		@Bean
		Runnable job() {
			return () -> {
			};
		}

		@Bean
		static BeanPostProcessor wrapJobs() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String name) {
					return "job".equals(name) ? ProxyFactory.getProxy(Runnable.class, (org.aopalliance.intercept.MethodInterceptor) (i) -> i.proceed()) : bean;
				}
			};
		}

	}

}
