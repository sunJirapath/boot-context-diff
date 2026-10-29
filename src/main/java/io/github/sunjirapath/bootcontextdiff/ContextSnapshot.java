package io.github.sunjirapath.bootcontextdiff;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * Fingerprint of an application context: bean name to exposed type, configuration source
 * to condition outcomes, and optional caller-supplied boolean checks.
 * Does not read property or environment values.
 *
 * @param schemaVersion version of the JSON layout, independent of the Spring Boot version
 * @param beans bean name to exposed type name
 * @param conditions configuration class or bean method to condition type to matched
 * @param checks explicitly supplied check name to boolean result; omitted from JSON when empty
 */
public record ContextSnapshot(int schemaVersion, Map<String, String> beans,
		Map<String, Map<String, Boolean>> conditions,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Boolean> checks) {

	public ContextSnapshot {
		checks = checks == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(checks));
		checks.forEach((name, result) -> {
			if (name.isBlank() || result == null) {
				throw new IllegalArgumentException("Checks require non-blank names and boolean results");
			}
		});
	}

	/** Retains the original constructor for structural snapshots and existing callers. */
	public ContextSnapshot(int schemaVersion, Map<String, String> beans,
			Map<String, Map<String, Boolean>> conditions) {
		this(schemaVersion, beans, conditions, Map.of());
	}

	static final int SCHEMA_VERSION = 1;

	static final int CHECKS_SCHEMA_VERSION = 2;

	private static final Pattern GENERATED_SUFFIX = Pattern
		.compile("\\$\\$(?:SpringCGLIB\\$\\$|EnhancerBy(?:Spring)?CGLIB\\$\\$|Lambda(?:\\$|/)).*");

	/** Reads the context without instantiating any bean. */
	public static ContextSnapshot of(ConfigurableApplicationContext context) {
		return of(context, Map.of());
	}

	/** Reads structure and records explicitly supplied boolean checks without evaluating them. */
	public static ContextSnapshot of(ConfigurableApplicationContext context, Map<String, Boolean> checks) {
		Objects.requireNonNull(checks, "checks");
		ConfigurableListableBeanFactory factory = context.getBeanFactory();
		// shortcut: manually registered singletons (no bean definition) are not listed
		Map<String, String> beans = new TreeMap<>();
		for (String name : factory.getBeanDefinitionNames()) {
			BeanDefinition definition = factory.getMergedBeanDefinition(name);
			if (!definition.isAbstract()) {
				beans.put(name, typeName(factory, name));
			}
		}
		Map<String, Map<String, Boolean>> conditions = new TreeMap<>();
		// find() creates a report and may instantiate a parent bean; read only the local singleton.
		Object singleton = factory.getSingleton("autoConfigurationReport");
		// shortcut: parent-context reports are ignored, snapshot each context separately
		if (singleton instanceof ConditionEvaluationReport report) {
			report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
				// sources that normalise to the same name (per-run $$ suffixes) must merge, not overwrite
				Map<String, Boolean> byType = conditions.computeIfAbsent(stable(source), (key) -> new TreeMap<>());
				// only booleans: outcome messages can echo property values
				outcomes.forEach((entry) -> byType.merge(conditionName(entry.getCondition()),
						entry.getOutcome().isMatch(), Boolean::logicalAnd));
			});
		}
		return new ContextSnapshot(checks.isEmpty() ? SCHEMA_VERSION : CHECKS_SCHEMA_VERSION, beans, conditions, checks);
	}

	// Lambda conditions are named Owner$$Lambda/0x<address> and anonymous ones have no simple name.
	private static String conditionName(Object condition) {
		Class<?> type = condition.getClass();
		if (!type.isSynthetic() && GENERATED_SUFFIX.matcher(type.getName()).find()) {
			type = ClassUtils.getUserClass(type);
		}
		if (Proxy.isProxyClass(type)) {
			return "proxy" + Arrays.stream(type.getInterfaces()).map(Class::getName).sorted().toList();
		}
		String name = type.getSimpleName();
		if (name.isEmpty()) {
			name = ClassUtils.getShortName(type);
		}
		return stable(name);
	}

	// Declared type first: runtime classes of proxied beans carry per-run counters ($Proxy45, $$SpringCGLIB$$0).
	private static String typeName(ConfigurableListableBeanFactory factory, String name) {
		Class<?> type;
		try {
			BeanDefinition definition = factory.getMergedBeanDefinition(name);
			type = definition.getResolvableType().resolve();
			// A FactoryBean exposes its product; an unresolved static factory exposes its method's return type.
			if (type == null || FactoryBean.class.isAssignableFrom(type)
					|| (definition.getFactoryMethodName() != null && type.getName().equals(definition.getBeanClassName()))) {
				type = factory.getType(name, false);
			}
		}
		catch (BeansException | TypeNotPresentException | LinkageError ex) {
			// a definition whose class cannot be loaded must not make the whole snapshot fail
			type = null;
		}
		if (type == null) {
			return "?";
		}
		if (Proxy.isProxyClass(type)) {
			return "proxy" + Arrays.stream(type.getInterfaces()).map(Class::getName).sorted().toList();
		}
		return stable(type.getName());
	}

	private static String stable(String name) {
		int method = name.indexOf('#');
		return method < 0 ? GENERATED_SUFFIX.matcher(name).replaceAll("")
				: GENERATED_SUFFIX.matcher(name.substring(0, method)).replaceAll("") + name.substring(method);
	}

	/** Human-readable differences from this baseline to {@code actual}; empty when equal. */
	List<String> diff(ContextSnapshot actual) {
		List<String> lines = new ArrayList<>();
		for (String name : union(beans, actual.beans)) {
			String was = beans.get(name);
			String now = actual.beans.get(name);
			if (now == null) {
				lines.add("REMOVED BEAN      " + name + " (was " + was + ")");
			}
			else if (was == null) {
				lines.add("ADDED BEAN        " + name + " (" + now + ")");
			}
			else if (!was.equals(now)) {
				lines.add("BEAN TYPE CHANGED " + name + ": " + was + " -> " + now);
			}
		}
		for (String source : union(conditions, actual.conditions)) {
			Map<String, Boolean> was = conditions.getOrDefault(source, Map.of());
			Map<String, Boolean> now = actual.conditions.getOrDefault(source, Map.of());
			for (String condition : union(was, now)) {
				if (!java.util.Objects.equals(was.get(condition), now.get(condition))) {
					lines.add("CONDITION CHANGED " + source + " [" + condition + "]: "
							+ outcome(was.get(condition)) + " -> " + outcome(now.get(condition)));
				}
			}
		}
		for (String name : union(checks, actual.checks)) {
			if (!Objects.equals(checks.get(name), actual.checks.get(name))) {
				lines.add("CHECK CHANGED     " + name + ": " + checkResult(checks.get(name)) + " -> "
						+ checkResult(actual.checks.get(name)));
			}
		}
		return lines;
	}

	private static String checkResult(Boolean result) {
		return result == null ? "not supplied" : result.toString();
	}

	private static String outcome(Boolean matched) {
		return (matched == null) ? "not evaluated" : matched ? "matched" : "not matched";
	}

	private static TreeSet<String> union(Map<String, ?> a, Map<String, ?> b) {
		TreeSet<String> keys = new TreeSet<>(a.keySet());
		keys.addAll(b.keySet());
		return keys;
	}

}
