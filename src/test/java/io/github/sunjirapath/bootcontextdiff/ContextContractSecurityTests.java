package io.github.sunjirapath.bootcontextdiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.context.annotation.Condition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

class ContextContractSecurityTests {

	@TempDir
	Path dir;

	static Stream<String> hostileBaselines() {
		return Stream.concat(Stream.concat(ContextContractValidationTests.invalidBaselines().stream(),
				ContextContractCheckTests.invalidCheckSections().stream().map((section) ->
						"{\"schemaVersion\":2,\"beans\":{},\"conditions\":{}" + section + "}")), Stream.of(
				"", "null", "[]", "{}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":null},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":null}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":null}}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},\"checks\":null}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":\"?\"},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},\"@class\":\"java.lang.ProcessBuilder\"}",
				"[".repeat(2000) + "0" + "]".repeat(2000), "{\"schemaVersion\":1,"));
	}

	@ParameterizedTest
	@MethodSource("hostileBaselines")
	@Timeout(10)
	void malformedBaselineAttackFailsClosedWithoutWritesOrRawParserCause(String input) throws Exception {
		// Kills unchecked parser exceptions, permissive decoding, and verify delegating to write.
		Path baseline = Files.writeString(dir.resolve("hostile.json"), input);
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("not a valid context baseline")
				.hasNoCause();
		}
		assertThat(Files.readString(baseline)).isEqualTo(input);
		try (var paths = Files.list(dir)) {
			assertThat(paths.toList()).containsExactly(baseline);
		}
	}

	@Test
	@Timeout(10)
	void oversizedBaselineAttackIsRejectedBeforeJsonAllocation() throws Exception {
		// Kills removal of the byte limit: whitespace plus a valid snapshot would otherwise verify.
		Path baseline = dir.resolve("huge.json");
		String input = " ".repeat(JsonCodec.MAX_BASELINE_BYTES)
				+ "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{}}";
		Files.writeString(baseline, input);
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("8 MiB");
		}
		assertThat(Files.size(baseline)).isEqualTo(input.length());
	}

	@Test
	@Timeout(10)
	void oversizedWriteAttackCannotDestroyThePreviousBaseline() throws Exception {
		// Kills writing a baseline that the bounded reader cannot verify, or checking size after replacement.
		Path baseline = dir.resolve("bounded-write.json");
		try (var context = context()) {
			ContextContract.write(context, baseline);
			byte[] before = Files.readAllBytes(baseline);
			context.registerBeanDefinition("x".repeat(JsonCodec.MAX_BASELINE_BYTES), new RootBeanDefinition(String.class));
			assertThatThrownBy(() -> ContextContract.write(context, baseline))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("8 MiB");
			assertThat(Files.readAllBytes(baseline)).isEqualTo(before);
		}
	}

	@Test
	void invalidUtf8AttackFailsWithoutReplacementOrWrites() throws Exception {
		// Kills permissive UTF-8 decoding and leaking CharacterCodingException as a different error type.
		Path baseline = dir.resolve("utf8.json");
		byte[] input = { (byte) 0xc3, 0x28 };
		Files.write(baseline, input);
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("not a valid context baseline");
		}
		assertThat(Files.readAllBytes(baseline)).isEqualTo(input);
	}

	@Test
	void missingAndDirectoryBaselineAttackNeverCreatesFiles() throws Exception {
		// Kills any auto-generation on a missing path or recovery after a directory read failure.
		Path missing = dir.resolve("missing/parents/baseline.json");
		Path directory = Files.createDirectory(dir.resolve("directory"));
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.verify(context, missing))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("ContextContract.write");
			assertThatThrownBy(() -> ContextContract.verify(context, directory))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("directory");
		}
		assertThat(dir.resolve("missing")).doesNotExist();
		try (var paths = Files.list(directory)) {
			assertThat(paths.toList()).isEmpty();
		}
	}

	@Test
	void symlinkBaselineAttackCannotVerifyOrOverwriteTarget() throws Exception {
		// Kills following the final symlink for either API, including a link to a perfectly valid baseline.
		Path target = dir.resolve("outside.json");
		Path link = dir.resolve("link.json");
		try (var context = context()) {
			ContextContract.write(context, target);
			byte[] before = Files.readAllBytes(target);
			createLinkOrSkip(() -> Files.createSymbolicLink(link, target));
			assertThatThrownBy(() -> ContextContract.verify(context, link))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("Symbolic links");
			assertThatThrownBy(() -> ContextContract.write(context, link))
				.isInstanceOf(UncheckedIOException.class);
			assertThat(Files.readAllBytes(target)).isEqualTo(before);
			assertThat(Files.isSymbolicLink(link)).isTrue();
		}
	}

	@Test
	void parentSymlinkTraversalAttackCannotEscapeThroughDotDot() throws Exception {
		// Kills checking only the normalized final path, which loses the symlink before '..'.
		Path outside = Files.createDirectories(dir.resolve("outside/child"));
		Path link = dir.resolve("linked-parent");
		createLinkOrSkip(() -> Files.createSymbolicLink(link, outside));
		Path attack = link.resolve("../escaped.json");
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.write(context, attack))
				.isInstanceOf(UncheckedIOException.class);
			assertThatThrownBy(() -> ContextContract.verify(context, attack))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("Symbolic links");
		}
		assertThat(dir.resolve("outside/escaped.json")).doesNotExist();
		assertThat(dir.resolve("escaped.json")).doesNotExist();
	}

	@Test
	void nonDirectoryTraversalAttackCannotBeNormalizedIntoWrite() throws Exception {
		// Kills normalizing away a non-directory parent before validating the original path.
		Path parent = Files.writeString(dir.resolve("plain-file"), "keep");
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.write(context, parent.resolve("../escaped.json")))
				.isInstanceOf(UncheckedIOException.class);
		}
		assertThat(Files.readString(parent)).isEqualTo("keep");
		assertThat(dir.resolve("escaped.json")).doesNotExist();
	}

	@Test
	void directoryRootWriteAttackCannotEscapeAsNullPointerException() throws Exception {
		// Kills dereferencing a null parent for a filesystem root; use a temporary ZIP filesystem, never the host root.
		URI uri = URI.create("jar:" + dir.resolve("filesystem.zip").toUri());
		try (var filesystem = FileSystems.newFileSystem(uri, Map.of("create", "true")); var context = context()) {
			assertThatThrownBy(() -> ContextContract.write(context, filesystem.getPath("/")))
				.isInstanceOf(UncheckedIOException.class);
		}
	}

	@Test
	void hardlinkOverwriteAttackReplacesDestinationWithoutTruncatingOtherFile() throws Exception {
		// Kills in-place write/truncate: the hard-linked witness would also change.
		Path baseline = dir.resolve("baseline.json");
		Path witness = dir.resolve("other-file.json");
		try (var context = context()) {
			ContextContract.write(context, baseline);
			createLinkOrSkip(() -> Files.createLink(witness, baseline));
			byte[] before = Files.readAllBytes(witness);
			context.registerBeanDefinition("added", new RootBeanDefinition(String.class));
			ContextContract.write(context, baseline);
			ContextContract.verify(context, baseline);
			assertThat(Files.readAllBytes(witness)).isEqualTo(before);
			assertThat(Files.readAllBytes(baseline)).isNotEqualTo(before);
		}
	}

	@Test
	void failedReplacementAttackPreservesDirectoryAndRemovesTemporaryFile() throws Exception {
		// Kills temporary-file cleanup removal after a failed atomic move.
		Path baseline = Files.createDirectory(dir.resolve("baseline.json"));
		Path marker = Files.writeString(baseline.resolve("keep"), "unchanged");
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.write(context, baseline))
				.isInstanceOf(UncheckedIOException.class);
		}
		assertThat(Files.readString(marker)).isEqualTo("unchanged");
		try (var paths = Files.list(dir)) {
			assertThat(paths.toList()).containsExactly(baseline);
		}
	}

	@Test
	@Timeout(30)
	void concurrentWriterAttackNeverExposesPartialJson() throws Exception {
		// Kills in-place truncation under competing writers/readers; the hardlink test is the deterministic counterpart.
		Path baseline = dir.resolve("concurrent.json");
		try (var first = context(); var second = context()) {
			for (int i = 0; i < 500; i++) {
				first.registerBeanDefinition("first-" + i, new RootBeanDefinition(String.class));
				second.registerBeanDefinition("second-" + i, new RootBeanDefinition(Integer.class));
			}
			ContextContract.write(first, baseline);
			var a = ContextSnapshot.of(first);
			var b = ContextSnapshot.of(second);
			var executor = Executors.newFixedThreadPool(3);
			try {
				List<Callable<Void>> tasks = new ArrayList<>();
				for (var current : List.of(first, second)) {
					tasks.add(() -> {
						int succeeded = 0;
						int ioFailures = 0;
						for (int i = 0; i < 40; i++) {
							try {
								ContextContract.write(current, baseline);
								succeeded++;
							}
							catch (UncheckedIOException ex) {
								assertThat(ex).hasRootCauseInstanceOf(IOException.class);
								ioFailures++;
							}
						}
						assertThat(succeeded).as("successful calls per writer").isPositive();
						assertThat(succeeded + ioFailures).isEqualTo(40);
						return null;
					});
				}
				tasks.add(() -> {
					for (int i = 0; i < 200; i++) {
						String json;
						try {
							json = Files.readString(baseline);
						}
						catch (IOException ex) {
							json = Files.readString(baseline);
						}
						assertThat(codec().read(json)).isIn(a, b);
					}
					return null;
				});
				for (var result : executor.invokeAll(tasks, 20, TimeUnit.SECONDS)) {
					result.get();
				}
			}
			finally {
				executor.shutdownNow();
			}
			assertThat(codec().read(Files.readString(baseline))).isIn(a, b);
			try (var paths = Files.newDirectoryStream(dir, ".context-baseline-*.tmp")) {
				assertThat(paths).isEmpty();
			}
		}
	}

	@Test
	void diffInjectionAttackEscapesControlMarkdownAndWorkflowCommands() throws Exception {
		// Kills raw identifier concatenation in every diff category, without changing comparison identities.
		String attack = "evil\n::error::forged\r\u001b[31m[click](https://invalid)*_`<tag>\u2028\u202e";
		Path baseline = dir.resolve("diagnostics.json");
		var hostile = new ContextSnapshot(2, Map.of(attack, attack, "same", attack),
				Map.of(attack, Map.of(attack, true)), Map.of(attack, true));
		Files.writeString(baseline, codec().write(hostile));
		try (var context = context()) {
			context.registerBeanDefinition("same", new RootBeanDefinition(String.class));
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.isExactlyInstanceOf(AssertionError.class)
				.hasMessageNotContaining(attack)
				.hasMessageNotContaining("::error::")
				.hasMessageNotContaining("\u001b")
				.hasMessageNotContaining("\r")
				.hasMessageNotContaining("[click]")
				.hasMessageNotContaining("<tag>")
				.hasMessageNotContaining("\u2028")
				.hasMessageNotContaining("\u202e")
				.hasMessageContainingAll("\\u000a", "\\u001b", "\\u003a\\u003aerror", "REMOVED BEAN",
						"BEAN TYPE CHANGED same", "CONDITION CHANGED", "CHECK CHANGED");
			Files.writeString(baseline, codec().write(ContextSnapshot.of(context)));
			context.registerBeanDefinition(attack, new RootBeanDefinition(String.class));
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.hasMessageContaining("ADDED BEAN")
				.hasMessageNotContaining(attack)
				.hasMessageNotContaining("::error::")
				.hasMessageNotContaining("\u001b");
		}
	}

	@Test
	void parserErrorInjectionAttackCannotEscapeThroughExceptionCauses() throws Exception {
		// Kills forwarding Jackson's unescaped property-name diagnostic or attaching its raw cause.
		Path baseline = dir.resolve("parser-message.json");
		Files.writeString(baseline, "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},\"evil\\n::error::\\u001b\":true}");
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.isExactlyInstanceOf(AssertionError.class).hasNoCause()
				.hasMessageNotContaining("::error::")
				.hasMessageNotContaining("\u001b")
				.hasMessageNotContaining("evil\n");
		}
	}

	@Test
	void outputExhaustionAttackBoundsIdentifiersAndDifferenceCount() throws Exception {
		// Kills either identifier truncation or the assertion's first-100-differences limit.
		Path baseline = dir.resolve("output.json");
		try (var context = context()) {
			ContextContract.write(context, baseline);
			for (int i = 0; i < 150; i++) {
				context.registerBeanDefinition("bean-" + i + "x".repeat(10_000), new RootBeanDefinition(String.class));
			}
			assertThatThrownBy(() -> ContextContract.verify(context, baseline)).satisfies((failure) -> {
				assertThat(failure).isExactlyInstanceOf(AssertionError.class);
				assertThat(failure.getMessage()).contains("150 difference(s)", "[truncated]", "first 100 differences shown")
					.doesNotContain("x".repeat(300));
				assertThat(failure.getMessage().length()).isLessThan(40_000);
			});
		}
	}

	@Test
	void absentEmptyConditionAttackCannotVerifyAsEqual() throws Exception {
		// Kills treating absent and empty condition-source maps as equal.
		Path baseline = dir.resolve("empty-source.json");
		Files.writeString(baseline, "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"Injected\":{}}}");
		try (var context = context()) {
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("CONDITION SOURCE CHANGED Injected");
		}
	}

	@Test
	void normalizationAttackCannotHideCaseWhitespaceOrLateTruncatedDifferences() throws Exception {
		// Kills normalizing comparison keys or comparing their truncated diagnostic representations.
		Path baseline = dir.resolve("identity.json");
		for (String changed : List.of("service", " Service ", "Service" + "x".repeat(300) + "b")) {
			try (var context = context()) {
				String original = changed.startsWith("Servicex") ? "Service" + "x".repeat(300) + "a" : "Service";
				context.registerBeanDefinition(original, new RootBeanDefinition(String.class));
				ContextContract.write(context, baseline);
				context.removeBeanDefinition(original);
				context.registerBeanDefinition(changed, new RootBeanDefinition(String.class));
				assertThatThrownBy(() -> ContextContract.verify(context, baseline))
					.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("2 difference(s)");
			}
		}
	}

	@Test
	void localeAndCrLfAttackCannotChangeOrderingOrSuppressBooleanDrift() throws Exception {
		// Kills platform/locale sorting or normalization that hides a true/false check transition.
		Locale previous = Locale.getDefault();
		Path baseline = dir.resolve("locale.json");
		try (var context = context()) {
			context.registerBeanDefinition("I", new RootBeanDefinition(String.class));
			context.registerBeanDefinition("i", new RootBeanDefinition(Integer.class));
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			ContextContract.write(context, baseline, Map.of("enabled", true));
			byte[] before = Files.readAllBytes(baseline);
			Locale.setDefault(Locale.US);
			ContextContract.write(context, baseline, Map.of("enabled", true));
			assertThat(Files.readAllBytes(baseline)).isEqualTo(before);
			Files.writeString(baseline, "\uFEFF" + Files.readString(baseline).replace("\n", "\r\n"), StandardCharsets.UTF_8);
			byte[] encoded = Files.readAllBytes(baseline);
			ContextContract.verify(context, baseline, Map.of("enabled", true));
			assertThatThrownBy(() -> ContextContract.verify(context, baseline, Map.of("enabled", false)))
				.isExactlyInstanceOf(AssertionError.class).hasMessageContaining("CHECK CHANGED");
			assertThat(Files.readAllBytes(baseline)).isEqualTo(encoded);
		}
		finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	void conditionMessageSecretAttackCannotLeakPropertyOrUrlCredentials() throws Exception {
		// Kills serializing ConditionOutcome messages instead of boolean outcomes.
		Path baseline = dir.resolve("message.json");
		try (var context = context()) {
			Condition condition = (ctx, metadata) -> true;
			ConditionEvaluationReport.get(context.getBeanFactory()).recordConditionEvaluation("Source", condition,
					new ConditionOutcome(true, "password=regression-secret https://user:token@example.invalid"));
			ContextContract.write(context, baseline);
			assertThat(Files.readString(baseline)).doesNotContain("regression-secret", "user:token", "password=");
		}
		try (var different = context()) {
			assertThatThrownBy(() -> ContextContract.verify(different, baseline)).isExactlyInstanceOf(AssertionError.class)
				.hasMessageContaining("CONDITION CHANGED")
				.hasMessageNotContaining("regression-secret")
				.hasMessageNotContaining("user:token")
				.hasMessageNotContaining("password=");
		}
	}

	@Test
	@Timeout(10)
	void regexBacktrackingAttackOnGeneratedNameNormalizationRemainsBounded() {
		// Kills replacing the fixed generated-name matcher with ambiguous nested-repeat matching.
		String source = "$".repeat(100_000) + "X";
		try (var context = context()) {
			Condition condition = (ctx, metadata) -> true;
			ConditionEvaluationReport.get(context.getBeanFactory()).recordConditionEvaluation(source, condition, ConditionOutcome.match());
			assertThat(ContextSnapshot.of(context).conditions()).containsOnlyKeys(source);
		}
	}

	@Test
	void identifierSecretLeakAttackMustNotCommitCredentials() throws Exception {
		String secret = "regression-secret-token-91384";
		Path baseline = dir.resolve("secret.json");
		try (var context = context()) {
			context.getEnvironment().getPropertySources().addFirst(
					new MapPropertySource("security-test", Map.of("app.api-token", secret)));
			context.registerBeanDefinition("bean-" + secret, new RootBeanDefinition(String.class));
			context.registerAlias("bean-" + secret, "alias-" + secret);
			Condition condition = (ctx, metadata) -> true;
			ConditionEvaluationReport.get(context.getBeanFactory()).recordConditionEvaluation("source-" + secret,
					condition, ConditionOutcome.match());
			assertThatThrownBy(() -> ContextContract.write(context, baseline, Map.of("check-" + secret, true)))
				.isInstanceOf(IllegalStateException.class)
				.satisfies((failure) -> {
					for (Throwable cause = (Throwable) failure; cause != null; cause = cause.getCause()) {
						if (cause.getMessage() != null) {
							assertThat(cause.getMessage()).doesNotContain(secret);
						}
					}
					assertThat(failure).hasMessageContaining("bean").hasMessageContaining("app.api-token");
				});
			assertThat(baseline).doesNotExist();
		}
	}

	@Test
	void manualSingletonSecurityDriftAttackMustNotPassVerification() {
		Path baseline = dir.resolve("singleton.json");
		try (var context = context()) {
			ContextContract.write(context, baseline);
			context.getBeanFactory().registerSingleton("securityOverride", new Object());
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("securityOverride", Object.class.getName());
			assertThatThrownBy(() -> ContextContract.verify(context, baseline)).isExactlyInstanceOf(AssertionError.class);
		}
	}

	private static void createLinkOrSkip(Callable<Path> creation) throws Exception {
		try {
			creation.call();
		}
		catch (FileSystemException | UnsupportedOperationException | SecurityException ex) {
			abort("Link creation unavailable: " + ex);
		}
	}

	private static GenericApplicationContext context() {
		var context = new GenericApplicationContext();
		context.refresh();
		return context;
	}

	private static JsonCodec codec() {
		return JsonCodec.select(ContextContract.class.getClassLoader());
	}

}
