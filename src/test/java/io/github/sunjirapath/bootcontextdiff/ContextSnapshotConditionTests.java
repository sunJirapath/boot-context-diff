package io.github.sunjirapath.bootcontextdiff;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ContextSnapshotConditionTests {

	@TempDir
	Path dir;

	@Test
	void snapshotDoesNotCreateAReportSingleton() {
		try (GenericApplicationContext context = context()) {
			String[] before = context.getBeanFactory().getSingletonNames();
			assertThat(ContextSnapshot.of(context).conditions()).isEmpty();
			assertThat(context.getBeanFactory().getSingletonNames()).containsExactly(before);
		}
	}

	@Test
	void snapshotDoesNotReadOrInstantiateAParentsReportBean() {
		AtomicInteger instantiated = new AtomicInteger();
		try (GenericApplicationContext parent = new GenericApplicationContext()) {
			parent.registerBean("autoConfigurationReport", String.class, () -> {
				instantiated.incrementAndGet();
				return "application bean";
			}, (definition) -> definition.setLazyInit(true));
			parent.refresh();
			try (GenericApplicationContext child = new GenericApplicationContext(parent)) {
				child.refresh();
				assertThatCode(() -> ContextSnapshot.of(child)).doesNotThrowAnyException();
				assertThat(instantiated).hasValue(0);
			}
		}
	}

	@Test
	void ordinaryApplicationSingletonWithTheReportNameDoesNotCrash() {
		try (GenericApplicationContext context = context()) {
			context.getBeanFactory().registerSingleton("autoConfigurationReport", "application bean");
			assertThat(ContextSnapshot.of(context).conditions()).isEmpty();
		}
	}

	@Test
	void parentOutcomesRemainExcludedEvenWhenBothContextsHaveReports() {
		try (GenericApplicationContext parent = context();
				GenericApplicationContext child = new GenericApplicationContext(parent)) {
			record(parent, "Parent", new FirstCondition(), false);
			child.refresh();
			record(child, "Child", new SecondCondition(), true);
			assertThat(ContextSnapshot.of(child).conditions())
				.containsOnlyKeys("Child")
				.containsEntry("Child", Map.of("SecondCondition", true));
		}
	}

	@Test
	void legitimateDollarSignsInSourceNamesRemainDistinct() {
		try (GenericApplicationContext context = context()) {
			record(context, "demo.Config$$East#feature", new FirstCondition(), true);
			record(context, "demo.Config$$West#feature", new FirstCondition(), false);
			assertThat(ContextSnapshot.of(context).conditions())
				.containsEntry("demo.Config$$East#feature", Map.of("FirstCondition", true))
				.containsEntry("demo.Config$$West#feature", Map.of("FirstCondition", false));
		}
	}

	@Test
	void methodNamesContainingDollarSignsAreNotErased() {
		try (GenericApplicationContext context = context()) {
			record(context, "demo.Config#feature$$East", new FirstCondition(), true);
			record(context, "demo.Config#feature$$West", new FirstCondition(), false);
			assertThat(ContextSnapshot.of(context).conditions())
				.containsOnlyKeys("demo.Config#feature$$East", "demo.Config#feature$$West");
		}
	}

	@Test
	void jdkProxyConditionKeysDoNotDependOnProxyClassCounter() {
		ClassLoader parent = Condition.class.getClassLoader();
		Condition a = proxyCondition(new ClassLoader(parent) { });
		Condition b = proxyCondition(new ClassLoader(parent) { });
		assertThat(a.getClass()).isNotEqualTo(b.getClass());
		try (GenericApplicationContext first = context(); GenericApplicationContext second = context()) {
			record(first, "Source", a, true);
			record(second, "Source", b, true);
			assertThat(ContextSnapshot.of(first).conditions()).isEqualTo(ContextSnapshot.of(second).conditions());
		}
	}

	@Test
	void legitimateDollarSignsInConditionClassNamesRemainDistinct() {
		try (GenericApplicationContext context = context()) {
			record(context, "Source", new First$$East(), true);
			record(context, "Source", new First$$West(), false);
			assertThat(ContextSnapshot.of(context).conditions()).containsEntry("Source",
					Map.of("First$$East", true, "First$$West", false));
		}
	}

	@Test
	void cglibConditionUsesTheSameKeyAsItsDeclaredClass() {
		ProxyFactory factory = new ProxyFactory(new FirstCondition());
		factory.setProxyTargetClass(true);
		Condition proxy = (Condition) factory.getProxy();
		try (GenericApplicationContext context = context()) {
			record(context, "Source", proxy, true);
			assertThat(ContextSnapshot.of(context).conditions()).containsEntry("Source", Map.of("FirstCondition", true));
		}
	}

	@Test
	void generatedSourceSuffixIsRemovedWithoutLosingMethodIdentity() {
		try (GenericApplicationContext context = context()) {
			record(context, "demo.Config$$SpringCGLIB$$10#one", new FirstCondition(), true);
			record(context, "demo.Config$$SpringCGLIB$$11#two", new FirstCondition(), false);
			assertThat(ContextSnapshot.of(context).conditions())
				.containsEntry("demo.Config#one", Map.of("FirstCondition", true))
				.containsEntry("demo.Config#two", Map.of("FirstCondition", false));
		}
	}

	@Test
	void duplicateSimpleConditionNamesUseLogicalAndInEitherOrder() {
		for (boolean reverse : new boolean[] { false, true }) {
			try (GenericApplicationContext context = context()) {
				record(context, "Source", new GroupA.SameName(), reverse);
				record(context, "Source", new GroupB.SameName(), !reverse);
				assertThat(ContextSnapshot.of(context).conditions()).containsEntry("Source", Map.of("SameName", false));
			}
		}
	}

	@Test
	void shuffledEvaluationOrderProducesIdenticalBytesAndDoesNotExposeOutcomeMessages() throws Exception {
		List<Integer> evaluations = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			evaluations.add(i);
		}
		byte[] expected = null;
		for (int seed = 0; seed < 10; seed++) {
			Collections.shuffle(evaluations, new Random(seed));
			try (GenericApplicationContext context = context()) {
				for (int i : evaluations) {
					Condition condition = (i % 2 == 0) ? new FirstCondition() : new SecondCondition();
					ConditionEvaluationReport.get(context.getBeanFactory()).recordConditionEvaluation(
							"Source" + (i % 7), condition, new ConditionOutcome(i % 3 == 0, "secret-token-" + i));
				}
				Path baseline = dir.resolve(seed + ".json");
				ContextContract.write(context, baseline);
				byte[] actual = Files.readAllBytes(baseline);
				assertThat(Files.readString(baseline)).doesNotContain("secret-token-");
				if (expected == null) {
					expected = actual;
				}
				assertThat(actual).isEqualTo(expected);
			}
		}
	}

	@ParameterizedTest
	@MethodSource("outcomeChanges")
	void everyConditionTransitionHasAnExactDiff(Boolean before, Boolean after, String description) {
		ContextSnapshot baseline = snapshot(before);
		ContextSnapshot actual = snapshot(after);
		assertThat(baseline.diff(actual)).containsExactly("CONDITION CHANGED Source [FirstCondition]: " + description);
		assertThat(baseline.diff(baseline)).isEmpty();
	}

	static Stream<Arguments> outcomeChanges() {
		return Stream.of(
				Arguments.of(true, false, "matched -> not matched"),
				Arguments.of(false, true, "not matched -> matched"),
				Arguments.of(null, true, "not evaluated -> matched"),
				Arguments.of(null, false, "not evaluated -> not matched"),
				Arguments.of(true, null, "matched -> not evaluated"),
				Arguments.of(false, null, "not matched -> not evaluated"));
	}

	@Test
	void diffIsSortedAndIncludesAllBeanAndConditionChanges() {
		Map<String, String> oldBeans = new LinkedHashMap<>();
		oldBeans.put("zRemoved", "Old");
		oldBeans.put("mChanged", "Old");
		Map<String, String> newBeans = new LinkedHashMap<>();
		newBeans.put("mChanged", "New");
		newBeans.put("aAdded", "New");
		ContextSnapshot before = new ContextSnapshot(1, oldBeans,
				Map.of("zSource", Map.of("B", true, "A", false), "aSource", Map.of("C", true)));
		ContextSnapshot after = new ContextSnapshot(1, newBeans, Map.of("zSource", Map.of("B", false, "C", true)));
		assertThat(before.diff(after)).containsExactly(
				"ADDED BEAN        aAdded (New)",
				"BEAN TYPE CHANGED mChanged: Old -> New",
				"REMOVED BEAN      zRemoved (was Old)",
				"CONDITION CHANGED aSource [C]: matched -> not evaluated",
				"CONDITION CHANGED zSource [A]: not matched -> not evaluated",
				"CONDITION CHANGED zSource [B]: matched -> not matched",
				"CONDITION CHANGED zSource [C]: not evaluated -> matched");
	}

	@Test
	void sourceOnlyOnOneSideIsReportedAsNotEvaluated() {
		ContextSnapshot before = new ContextSnapshot(1, Map.of(), Map.of("Removed", Map.of("C", false)));
		ContextSnapshot after = new ContextSnapshot(1, Map.of(), Map.of("Added", Map.of("C", true)));
		assertThat(before.diff(after)).containsExactly(
				"CONDITION CHANGED Added [C]: not evaluated -> matched",
				"CONDITION CHANGED Removed [C]: not matched -> not evaluated");
	}

	private static ContextSnapshot snapshot(Boolean outcome) {
		return new ContextSnapshot(1, Map.of(), outcome == null ? Map.of() : Map.of("Source", Map.of("FirstCondition", outcome)));
	}

	private static GenericApplicationContext context() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.refresh();
		return context;
	}

	private static void record(GenericApplicationContext context, String source, Condition condition, boolean matched) {
		ConditionEvaluationReport.get(context.getBeanFactory()).recordConditionEvaluation(source, condition,
				new ConditionOutcome(matched, "excluded message"));
	}

	private static Condition proxyCondition(ClassLoader loader) {
		return (Condition) Proxy.newProxyInstance(loader, new Class<?>[] { Condition.class }, (proxy, method, args) -> {
			return switch (method.getName()) {
				case "equals" -> proxy == args[0];
				case "hashCode" -> System.identityHashCode(proxy);
				case "toString" -> "condition";
				default -> true;
			};
		});
	}

	static class FirstCondition implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return true;
		}
	}

	static class SecondCondition extends FirstCondition {
	}

	static class First$$East extends FirstCondition {
	}

	static class First$$West extends FirstCondition {
	}

	static class GroupA {
		static class SameName extends FirstCondition {
		}
	}

	static class GroupB {
		static class SameName extends FirstCondition {
		}
	}

}
