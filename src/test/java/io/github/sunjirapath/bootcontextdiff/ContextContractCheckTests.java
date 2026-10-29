package io.github.sunjirapath.bootcontextdiff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextContractCheckTests {

	@TempDir
	Path dir;

	private final ApplicationContextRunner runner = new ApplicationContextRunner();

	@Test
	void unchangedChecksMatchAcrossContexts() {
		Path baseline = dir.resolve("checks.json");
		runner.run((ctx) -> ContextContract.write(ctx, baseline, Map.of("GraphiQL enabled", true)));
		runner.run((ctx) -> ContextContract.verify(ctx, baseline, Map.of("GraphiQL enabled", true)));
	}

	@Test
	void featureFlagChangeWithIdenticalStructureIsDetectedWithoutChangingBaseline() throws Exception {
		Path baseline = dir.resolve("checks.json");
		Path structural = dir.resolve("structure.json");
		runner.withPropertyValues("spring.graphql.graphiql.enabled=true").run((ctx) -> {
			ContextContract.write(ctx, structural);
			ContextContract.write(ctx, baseline, Map.of("GraphiQL enabled",
					ctx.getEnvironment().getProperty("spring.graphql.graphiql.enabled", Boolean.class, false)));
		});
		byte[] before = Files.readAllBytes(baseline);
		runner.withPropertyValues("spring.graphql.graphiql.enabled=false").run((ctx) -> {
			ContextContract.verify(ctx, structural);
			assertThatThrownBy(() -> ContextContract.verify(ctx, baseline, Map.of("GraphiQL enabled",
					ctx.getEnvironment().getProperty("spring.graphql.graphiql.enabled", Boolean.class, false))))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("CHECK CHANGED     GraphiQL enabled: true -> false");
		});
		assertThat(Files.readAllBytes(baseline)).isEqualTo(before);
	}

	@Test
	void removingChecksByUsingOriginalApiFailsClosed() {
		Path baseline = dir.resolve("checks.json");
		runner.run((ctx) -> {
			ContextContract.write(ctx, baseline, Map.of("GraphiQL enabled", true));
			assertThatThrownBy(() -> ContextContract.verify(ctx, baseline))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("CHECK CHANGED     GraphiQL enabled: true -> not supplied");
		});
	}

	@Test
	void introducingChecksRequiresDeliberateBaselineUpdate() {
		Path baseline = dir.resolve("legacy.json");
		runner.run((ctx) -> {
			ContextContract.write(ctx, baseline);
			assertThatThrownBy(() -> ContextContract.verify(ctx, baseline, Map.of("GraphiQL enabled", false)))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("CHECK CHANGED     GraphiQL enabled: not supplied -> false");
		});
	}

	@Test
	void removedAndAddedChecksAreReportedInSortedOrder() {
		runner.run((ctx) -> {
			var before = ContextSnapshot.of(ctx, Map.of("z", false, "b", false));
			var after = ContextSnapshot.of(ctx, Map.of("a", true, "b", true));
			assertThat(before.diff(after)).containsExactly("CHECK CHANGED     a: not supplied -> true",
					"CHECK CHANGED     b: false -> true", "CHECK CHANGED     z: false -> not supplied");
		});
	}

	@Test
	void checkOutputIsSortedAndDefensivelyCopied() throws Exception {
		Path a = dir.resolve("a.json");
		Path b = dir.resolve("b.json");
		Map<String, Boolean> checks = new LinkedHashMap<>();
		checks.put("z", false);
		checks.put("a", true);
		runner.run((ctx) -> {
			ContextContract.write(ctx, a, checks);
			ContextContract.write(ctx, b, Map.of("a", true, "z", false));
			var snapshot = ContextSnapshot.of(ctx, checks);
			checks.put("a", false);
			assertThat(snapshot.checks()).containsEntry("a", true);
			assertThatThrownBy(() -> snapshot.checks().put("extra", true)).isInstanceOf(UnsupportedOperationException.class);
		});
		assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
		assertThat(Files.readString(a)).startsWith("{\n  \"schemaVersion\" : 2,").doesNotContain("\r");
	}

	@Test
	void originalApiKeepsVersionOneFormatAndConstructor() throws Exception {
		Path baseline = dir.resolve("legacy.json");
		runner.run((ctx) -> {
			ContextContract.write(ctx, baseline);
			ContextContract.verify(ctx, baseline, Map.of());
			var snapshot = ContextSnapshot.of(ctx);
			assertThat(new ContextSnapshot(1, snapshot.beans(), snapshot.conditions())).isEqualTo(snapshot);
		});
		assertThat(Files.readString(baseline)).startsWith("{\n  \"schemaVersion\" : 1,").doesNotContain("\"checks\"");
	}

	@Test
	void capturingChecksDoesNotCreateLazyBeansOrReadSecrets() throws Exception {
		Path baseline = dir.resolve("checks.json");
		AtomicInteger created = new AtomicInteger();
		runner.withPropertyValues("password=super-secret-value")
			.withBean("lazy", String.class, () -> { created.incrementAndGet(); return "secret"; },
					(definition) -> definition.setLazyInit(true))
			.run((ctx) -> {
			ContextContract.write(ctx, baseline, Map.of("HTTP probe passed", true));
			ContextContract.verify(ctx, baseline, Map.of("HTTP probe passed", true));
		});
		assertThat(created).hasValue(0);
		assertThat(Files.readString(baseline)).doesNotContain("super-secret-value", "password");
	}

	@Test
	void invalidCallerChecksCannotOverwriteBaseline() throws Exception {
		Path baseline = Files.writeString(dir.resolve("checks.json"), "keep me");
		Map<String, Boolean> nullResult = new HashMap<>();
		nullResult.put("probe", null);
		runner.run((ctx) -> {
			for (Map<String, Boolean> checks : List.of(Map.of(" ", true), nullResult)) {
				assertThatThrownBy(() -> ContextContract.write(ctx, baseline, checks))
					.isInstanceOf(IllegalArgumentException.class);
			}
			assertThatThrownBy(() -> ContextContract.write(ctx, baseline, null)).isInstanceOf(NullPointerException.class);
		});
		assertThat(Files.readString(baseline)).isEqualTo("keep me");
	}

	@ParameterizedTest
	@MethodSource("invalidCheckSections")
	void invalidCheckSectionsAreRejectedWithoutChangingBaseline(String section) throws Exception {
		Path baseline = dir.resolve("invalid.json");
		String content = "{\"schemaVersion\":2,\"beans\":{},\"conditions\":{}" + section + "}";
		Files.writeString(baseline, content);
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, baseline, Map.of("probe", true)))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining(baseline.toString()));
		assertThat(Files.readString(baseline)).isEqualTo(content);
	}

	static List<String> invalidCheckSections() {
		return List.of("", ",\"checks\":null", ",\"checks\":{}", ",\"checks\":[]", ",\"checks\":true",
				",\"checks\":{\"probe\":null}", ",\"checks\":{\"probe\":\"true\"}", ",\"checks\":{\"probe\":1}",
				",\"checks\":{\"probe\":0.0}", ",\"checks\":{\"probe\":[]}", ",\"checks\":{\"probe\":{}}",
				",\"checks\":{\"probe\":false,\"probe\":true}", ",\"checks\":{\" \":true}");
	}

	@Test
	void legacyVersionCannotHideNewChecks() throws Exception {
		Path baseline = dir.resolve("legacy.json");
		Files.writeString(baseline, "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},\"checks\":{\"probe\":true}}");
		runner.run((ctx) -> assertThatThrownBy(() -> ContextContract.verify(ctx, baseline, Map.of("probe", true)))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("schemaVersion"));
	}
}
