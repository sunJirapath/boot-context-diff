package io.github.sunjirapath.bootcontextdiff;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.util.ClassUtils;

/**
 * Fingerprint of an application context: bean name to exposed type, configuration source
 * to condition outcomes, and optional caller-supplied boolean checks.
 * Reads values only for properties with sensitive-looking keys to refuse identifiers that contain those values.
 * Sensitive values are never stored or included in diagnostics; callers must still keep secrets out of identifiers.
 *
 * @param schemaVersion version of the JSON layout, independent of the Spring Boot version
 * @param beans bean name to exposed type name; aliases map to {@code alias:<bean name>}
 * @param conditions configuration class or bean method to condition type to matched
 * @param checks explicitly supplied check name to boolean result; omitted from JSON when empty
 */
public record ContextSnapshot(int schemaVersion, Map<String, String> beans,
		Map<String, Map<String, Boolean>> conditions,
		Map<String, Boolean> checks) {

	public ContextSnapshot {
		// Canonical key order, so equivalent snapshots serialize identically whatever order the caller supplied.
		beans = beans == null ? null : Collections.unmodifiableMap(new TreeMap<>(beans));
		if (conditions != null) {
			Map<String, Map<String, Boolean>> sorted = new TreeMap<>();
			conditions.forEach((source, byType) ->
				sorted.put(source, byType == null ? null : Collections.unmodifiableMap(new TreeMap<>(byType))));
			conditions = Collections.unmodifiableMap(sorted);
		}
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
	private static final java.util.Set<String> INTERNAL_SINGLETONS = java.util.Set.of(
		ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME,
		ConfigurableApplicationContext.SYSTEM_PROPERTIES_BEAN_NAME,
		ConfigurableApplicationContext.SYSTEM_ENVIRONMENT_BEAN_NAME,
		ConfigurableApplicationContext.APPLICATION_STARTUP_BEAN_NAME,
		AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME,
		AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
		AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME,
		"springBootBanner",
		"autoConfigurationReport",
		"org.springframework.boot.autoconfigure.AutoConfigurationPackages",
		"org.springframework.context.annotation.ConfigurationClassPostProcessor.importRegistry",
		// Registered by SpringApplication and the Boot test support in a @SpringBootTest context.
		"springApplicationArguments",
		"springBootLoggerGroups",
		"springBootLoggingLifecycle",
		"springBootLoggingSystem",
		"org.springframework.boot.context.ContextIdApplicationContextInitializer$ContextId",
		"org.springframework.boot.test.context.PropertyMappingContextCustomizer$PropertyMappingCheckBeanPostProcessor",
		"org.springframework.boot.test.context.filter.TestTypeExcludeFilter");

	/** Reads the context without instantiating any bean. */
	public static ContextSnapshot of(ConfigurableApplicationContext context) {
		return of(context, Map.of());
	}

	/** Reads structure and records explicitly supplied boolean checks without evaluating them. */
	public static ContextSnapshot of(ConfigurableApplicationContext context, Map<String, Boolean> checks) {
		Objects.requireNonNull(checks, "checks");
		ConfigurableListableBeanFactory factory = context.getBeanFactory();
		List<SensitiveProperty> sensitiveProperties = sensitiveProperties(context.getEnvironment());
		Map<String, String> beans = new TreeMap<>();
		for (String name : factory.getBeanDefinitionNames()) {
			refuseSensitiveIdentifier("bean", name, sensitiveProperties);
			BeanDefinition definition = factory.getMergedBeanDefinition(name);
			if (!definition.isAbstract()) {
				beans.put(name, typeName(factory, name));
				for (String alias : factory.getAliases(name)) {
					refuseSensitiveIdentifier("alias", alias, sensitiveProperties);
					if (factory.containsBeanDefinition(alias)) {
						throw new IllegalStateException("Incomplete alias analysis: alias shadows definition " + DiagnosticText.of(alias));
					}
					beans.put(alias, "alias:" + name);
				}
			}
		}
		for (String name : factory.getSingletonNames()) {
			if (factory.containsBeanDefinition(name) || INTERNAL_SINGLETONS.contains(name)) {
				continue;
			}
			refuseSensitiveIdentifier("bean", name, sensitiveProperties);
			Object singletonBean = factory.getSingleton(name);
			if (singletonBean != null) {
				beans.put(name, exposedTypeName(singletonBean.getClass()));
				for (String alias : factory.getAliases(name)) {
					refuseSensitiveIdentifier("alias", alias, sensitiveProperties);
					if (factory.containsBeanDefinition(alias)) {
						throw new IllegalStateException("Incomplete alias analysis: alias shadows definition " + DiagnosticText.of(alias));
					}
					beans.put(alias, "alias:" + name);
				}
			}
		}
		Map<String, Map<String, Boolean>> conditions = new TreeMap<>();
		// find() creates a report and may instantiate a parent bean; read only the local singleton.
		Object singleton = factory.getSingleton("autoConfigurationReport");
		// shortcut: parent-context reports are ignored, snapshot each context separately
		if (singleton instanceof ConditionEvaluationReport report) {
			report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
				String stableSource = stable(source);
				refuseSensitiveIdentifier("condition source", stableSource, sensitiveProperties);
				Map<String, Boolean> byType = new TreeMap<>();
				if (conditions.putIfAbsent(stableSource, byType) != null) {
					throw new IllegalStateException("Incomplete condition analysis: colliding source " + DiagnosticText.of(stableSource));
				}
				// only booleans: outcome messages can echo property values
				outcomes.forEach((entry) -> {
					String condition = conditionName(entry.getCondition());
					if (byType.putIfAbsent(condition, entry.getOutcome().isMatch()) != null) {
						throw new IllegalStateException("Incomplete condition analysis: colliding condition "
							+ DiagnosticText.of(condition) + " at " + DiagnosticText.of(stableSource));
					}
				});
			});
		}
		checks.keySet().forEach((name) -> refuseSensitiveIdentifier("check", name, sensitiveProperties));
		return new ContextSnapshot(checks.isEmpty() ? SCHEMA_VERSION : CHECKS_SCHEMA_VERSION, beans, conditions, checks);
	}

	private record SensitiveProperty(String key, String value) {
	}

	private static List<SensitiveProperty> sensitiveProperties(ConfigurableEnvironment environment) {
		List<SensitiveProperty> properties = new ArrayList<>();
		for (var source : environment.getPropertySources()) {
			if (source instanceof EnumerablePropertySource<?> enumerable) {
				String[] keys;
				try {
					keys = enumerable.getPropertyNames();
				}
				catch (RuntimeException ex) {
					continue;
				}
				for (String key : keys) {
					if (sensitiveKey(key)) {
						String value = environment.getProperty(key);
						if (value != null && !value.isBlank() && value.length() >= 4) {
							properties.add(new SensitiveProperty(key, value));
						}
					}
				}
			}
		}
		return properties;
	}

	private static boolean sensitiveKey(String key) {
		String lower = key.toLowerCase(Locale.ROOT);
		return List.of("password", "passwd", "secret", "token", "credential", "apikey", "api-key", "api_key",
				"private-key", "privatekey", "access-key", "accesskey").stream().anyMatch(lower::contains)
				|| lower.endsWith(".key") || lower.endsWith("-key") || lower.endsWith("_key");
	}

	private static void refuseSensitiveIdentifier(String kind, String identifier, List<SensitiveProperty> properties) {
		for (SensitiveProperty property : properties) {
			if (identifier.contains(property.value())) {
				throw new IllegalStateException("Sensitive property '" + property.key()
						+ "' value appears in " + kind + " identifier; refusing snapshot");
			}
		}
	}

	// Lambda class names carry runtime addresses; keep their owner identity and reject collisions.
	private static String conditionName(Object condition) {
		Class<?> type = condition.getClass();
		if (!type.isSynthetic() && GENERATED_SUFFIX.matcher(type.getName()).find()) {
			type = ClassUtils.getUserClass(type);
		}
		if (Proxy.isProxyClass(type)) {
			return "proxy" + Arrays.stream(type.getInterfaces()).map(Class::getName).sorted().toList();
		}
		return stable(type.getName());
	}

	private static String exposedTypeName(Class<?> type) {
		if (Proxy.isProxyClass(type)) {
			return "proxy" + Arrays.stream(type.getInterfaces()).map(Class::getName).sorted().toList();
		}
		return stable(type.getName());
	}

	// Declared type first: runtime classes of proxied beans carry per-run counters ($Proxy45, $$SpringCGLIB$$0).
	private static String typeName(ConfigurableListableBeanFactory factory, String name) {
		Class<?> type;
		try {
			BeanDefinition definition = factory.getMergedBeanDefinition(name);
			type = definition.getResolvableType().resolve();
			if (type == null
					|| (definition.getFactoryMethodName() != null && type.getName().equals(definition.getBeanClassName()))) {
				// Dereference first: getType(name, false) still calls an existing FactoryBean's getObjectType().
				type = factory.getType("&" + name, false);
				if (type == null) {
					type = factory.getType(name, false);
				}
			}
			if (type != null && FactoryBean.class.isAssignableFrom(type)) {
				Object attribute = definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
				if (attribute instanceof Class<?> product) {
					type = product;
				}
				else if (attribute instanceof ResolvableType product) {
					type = product.resolve();
				}
				else if (attribute == null) {
					type = definition.getResolvableType().as(FactoryBean.class).getGeneric().resolve();
				}
				else {
					throw new IllegalStateException("Invalid FactoryBean product metadata for " + DiagnosticText.of(name));
				}
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
				lines.add("REMOVED BEAN      " + DiagnosticText.of(name) + " (was " + DiagnosticText.of(was) + ")");
			}
			else if (was == null) {
				lines.add("ADDED BEAN        " + DiagnosticText.of(name) + " (" + DiagnosticText.of(now) + ")");
			}
			else if (!was.equals(now)) {
				lines.add("BEAN TYPE CHANGED " + DiagnosticText.of(name) + ": " + DiagnosticText.of(was) + " -> " + DiagnosticText.of(now));
			}
		}
		for (String source : union(conditions, actual.conditions)) {
			Map<String, Boolean> was = conditions.getOrDefault(source, Map.of());
			Map<String, Boolean> now = actual.conditions.getOrDefault(source, Map.of());
			if (was.isEmpty() && now.isEmpty() && conditions.containsKey(source) != actual.conditions.containsKey(source)) {
				lines.add("CONDITION SOURCE CHANGED " + DiagnosticText.of(source) + ": "
						+ (conditions.containsKey(source) ? "evaluated -> not evaluated" : "not evaluated -> evaluated"));
			}
			for (String condition : union(was, now)) {
				if (!java.util.Objects.equals(was.get(condition), now.get(condition))) {
					lines.add("CONDITION CHANGED " + DiagnosticText.of(source) + " [" + DiagnosticText.of(condition) + "]: "
							+ outcome(was.get(condition)) + " -> " + outcome(now.get(condition)));
				}
			}
		}
		for (String name : union(checks, actual.checks)) {
			if (!Objects.equals(checks.get(name), actual.checks.get(name))) {
				lines.add("CHECK CHANGED     " + DiagnosticText.of(name) + ": " + checkResult(checks.get(name)) + " -> "
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
