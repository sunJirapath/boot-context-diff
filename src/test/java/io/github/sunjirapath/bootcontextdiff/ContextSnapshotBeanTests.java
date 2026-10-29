package io.github.sunjirapath.bootcontextdiff;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.ChildBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.ResolvableType;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSnapshotBeanTests {

	@Test
	void genericFactoryBeanUsesProductTypeWithoutCreatingFactoryOrProduct() {
		try (GenericApplicationContext context = emptyContext()) {
			AtomicInteger creations = new AtomicInteger();
			RootBeanDefinition definition = new RootBeanDefinition(StringFactory.class, () -> {
				creations.incrementAndGet();
				throw new AssertionError("Factory must not be created");
			});
			definition.setLazyInit(true);
			context.registerBeanDefinition("factory", definition);
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("factory", String.class.getName());
			assertThat(creations).hasValue(0);
			assertThat(context.getBeanFactory().containsSingleton("factory")).isFalse();
		}
	}

	@Test
	void factoryBeanObjectTypeAttributeTakesPrecedenceOverFactoryClass() {
		try (GenericApplicationContext context = emptyContext()) {
			RootBeanDefinition definition = new RootBeanDefinition(UnknownFactory.class);
			definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, ResolvableType.forClass(Runnable.class));
			context.registerBeanDefinition("factory", definition);
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("factory", Runnable.class.getName());
		}
	}

	@Test
	void factoryBeanWithUnknownProductIsUnknownWithoutInitialization() {
		try (GenericApplicationContext context = emptyContext()) {
			context.registerBeanDefinition("factory", new RootBeanDefinition(UnknownFactory.class));
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("factory", "?");
		}
	}

	@Test
	void factoryBeanProductTypeChangeIsReported() {
		try (GenericApplicationContext before = emptyContext(); GenericApplicationContext after = emptyContext()) {
			RootBeanDefinition first = new RootBeanDefinition(UnknownFactory.class);
			first.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, String.class);
			RootBeanDefinition second = new RootBeanDefinition(UnknownFactory.class);
			second.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, Integer.class);
			before.registerBeanDefinition("factory", first);
			after.registerBeanDefinition("factory", second);
			assertThat(ContextSnapshot.of(before).diff(ContextSnapshot.of(after)))
				.containsExactly("BEAN TYPE CHANGED factory: java.lang.String -> java.lang.Integer");
		}
	}

	@Test
	void staticFactoryMethodUsesReturnTypeWithoutInvokingMethod() {
		try (GenericApplicationContext context = emptyContext()) {
			RootBeanDefinition definition = new RootBeanDefinition(StaticMethods.class);
			definition.setFactoryMethodName("string");
			context.registerBeanDefinition("method", definition);
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("method", "java.lang.String");
		}
	}

	@Test
	void instanceFactoryMethodUsesReturnTypeWithoutCreatingFactory() {
		try (GenericApplicationContext context = emptyContext()) {
			RootBeanDefinition owner = new RootBeanDefinition(InstanceMethods.class, () -> {
				throw new AssertionError("Factory owner must not be created");
			});
			context.registerBeanDefinition("owner", owner);
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setFactoryBeanName("owner");
			definition.setFactoryMethodName("string");
			context.registerBeanDefinition("method", definition);
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("method", "java.lang.String");
			assertThat(context.getBeanFactory().containsSingleton("owner")).isFalse();
		}
	}

	@Test
	void scopedProxyUsesNativeProductMetadataWithoutCreatingTarget() {
		try (GenericApplicationContext context = emptyContext()) {
			RootBeanDefinition target = new RootBeanDefinition(String.class, () -> {
				throw new AssertionError("Scoped target must not be created");
			});
			target.setScope("request");
			BeanDefinitionHolder holder = ScopedProxyUtils.createScopedProxy(new BeanDefinitionHolder(target, "scoped"), context, true);
			context.registerBeanDefinition(holder.getBeanName(), holder.getBeanDefinition());
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("scoped", context.getBeanFactory().getType("scoped", false).getName())
				.containsEntry("scopedTarget.scoped", "java.lang.String");
			assertThat(context.getBeanFactory().containsSingleton("scoped")).isFalse();
			assertThat(context.getBeanFactory().containsSingleton("scopedTarget.scoped")).isFalse();
		}
	}

	@Test
	void existingFactoryCanExposeRuntimeProductTypeWithoutCreatingProduct() {
		try (GenericApplicationContext context = emptyContext()) {
			context.registerBeanDefinition("factory", new RootBeanDefinition(ExistingFactory.class));
			context.getBeanFactory().registerSingleton("factory", new ExistingFactory());
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("factory", "java.lang.String");
		}
	}

	@Test
	void ordinaryLazyAndPrototypeBeansAreNotCreated() {
		try (GenericApplicationContext context = emptyContext()) {
			AtomicInteger creations = new AtomicInteger();
			for (String scope : new String[] { BeanDefinition.SCOPE_SINGLETON, BeanDefinition.SCOPE_PROTOTYPE, "request" }) {
				RootBeanDefinition definition = new RootBeanDefinition(String.class, () -> {
					creations.incrementAndGet();
					throw new AssertionError("Bean must not be created");
				});
				definition.setScope(scope);
				definition.setLazyInit(true);
				context.registerBeanDefinition(scope, definition);
			}
			assertThat(ContextSnapshot.of(context).beans()).isEqualTo(Map.of(
				"singleton", "java.lang.String", "prototype", "java.lang.String", "request", "java.lang.String"));
			assertThat(creations).hasValue(0);
		}
	}

	@Test
	void inheritedDefinitionResolvesTypeAndAbstractTemplateIsExcluded() {
		try (GenericApplicationContext context = emptyContext()) {
			RootBeanDefinition template = new RootBeanDefinition(String.class);
			template.setAbstract(true);
			context.registerBeanDefinition("template", template);
			context.registerBeanDefinition("child", new ChildBeanDefinition("template"));
			assertThat(ContextSnapshot.of(context).beans()).isEqualTo(Map.of("child", "java.lang.String"));
		}
	}

	@Test
	void parentDefinitionsAliasesAndManualSingletonsAreExcluded() {
		try (GenericApplicationContext parent = emptyContext(); GenericApplicationContext child = new GenericApplicationContext(parent)) {
			parent.registerBeanDefinition("parentOnly", new RootBeanDefinition(String.class));
			child.refresh();
			child.registerBeanDefinition("local", new RootBeanDefinition(Integer.class));
			child.registerAlias("local", "alias");
			child.getBeanFactory().registerSingleton("manual", "secret");
			assertThat(ContextSnapshot.of(child).beans()).isEqualTo(Map.of("local", "java.lang.Integer"));
		}
	}

	@Test
	void legitimateDoubleDollarClassNameIsPreserved() {
		try (GenericApplicationContext context = emptyContext()) {
			context.registerBeanDefinition("named", new RootBeanDefinition(User$$Named.class));
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("named", User$$Named.class.getName());
		}
	}

	@Test
	void actualCglibProxyClassNormalizesToTargetType() {
		try (GenericApplicationContext context = emptyContext()) {
			ProxyFactory proxy = new ProxyFactory(new ProxyTarget());
			proxy.setProxyTargetClass(true);
			Class<?> proxyClass = proxy.getProxy().getClass();
			assertThat(proxyClass.getName()).contains("$$SpringCGLIB$$");
			context.registerBeanDefinition("proxy", new RootBeanDefinition(proxyClass));
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("proxy", ProxyTarget.class.getName());
		}
	}

	@Test
	void jdkProxyInterfacesAreSortedAndProxyClassCountersAreExcluded() {
		try (GenericApplicationContext context = emptyContext()) {
			Class<?> proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Runnable.class, AutoCloseable.class },
				(instance, method, arguments) -> { throw new AssertionError("Proxy must not be invoked"); }).getClass();
			context.registerBeanDefinition("proxy", new RootBeanDefinition(proxy));
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("proxy", "proxy[java.lang.AutoCloseable, java.lang.Runnable]");
		}
	}

	@Test
	void unresolvedSupplierTypeDoesNotInvokeSupplier() {
		try (GenericApplicationContext context = emptyContext()) {
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setInstanceSupplier(() -> { throw new AssertionError("Supplier must not be called"); });
			context.registerBeanDefinition("unknown", definition);
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("unknown", "?");
		}
	}

	@Test
	void beanClassWithMissingDependencyDoesNotCrashSnapshot() {
		try (GenericApplicationContext context = emptyContext()) {
			context.getBeanFactory().setBeanClassLoader(new ClassLoader(getClass().getClassLoader()) {
				@Override
				protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
					if (name.equals("broken.Dependency")) {
						throw new NoClassDefFoundError("missing/transitive/Dependency");
					}
					return super.loadClass(name, resolve);
				}
			});
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setBeanClassName("broken.Dependency");
			context.registerBeanDefinition("broken", definition);
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("broken", "?");
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void factoryWithMissingGenericProductDependencyDoesNotCrashSnapshot(boolean missingSuperclass) throws Exception {
		String factoryName = MissingProductFactory.class.getName();
		String productName = MissingProduct.class.getName();
		String missingName = missingSuperclass ? MissingProductBase.class.getName() : productName;
		ClassLoader isolated = new ClassLoader(getClass().getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.equals(missingName)) {
					throw new ClassNotFoundException(name);
				}
				if (name.equals(factoryName) || name.equals(productName)) {
					Class<?> loaded = findLoadedClass(name);
					if (loaded != null) {
						return loaded;
					}
					try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
						if (input == null) {
							throw new ClassNotFoundException(name);
						}
						byte[] bytes = input.readAllBytes();
						return defineClass(name, bytes, 0, bytes.length);
					}
					catch (IOException ex) {
						throw new ClassNotFoundException(name, ex);
					}
				}
				return super.loadClass(name, resolve);
			}
		};
		try (GenericApplicationContext context = emptyContext()) {
			Class<?> factory = isolated.loadClass(factoryName);
			assertThat(FactoryBean.class.isAssignableFrom(factory)).isTrue();
			context.registerBeanDefinition("missingGeneric", new RootBeanDefinition(factory));
			assertThat(ContextSnapshot.of(context).beans()).containsEntry("missingGeneric", "?");
		}
	}

	private static GenericApplicationContext emptyContext() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.refresh();
		return context;
	}

	static class StringFactory implements FactoryBean<String> {
		@Override public String getObject() { throw new AssertionError("Product must not be created"); }
		@Override public Class<?> getObjectType() { return String.class; }
	}

	static class StaticMethods {
		static String string() { throw new AssertionError("Factory method must not be invoked"); }
	}

	static class User$$Named extends Number {
		@Override public int intValue() { return 0; }
		@Override public long longValue() { return 0; }
		@Override public float floatValue() { return 0; }
		@Override public double doubleValue() { return 0; }
	}

	static class ProxyTarget {
		String value() { return "value"; }
	}

	static class InstanceMethods {
		String string() { throw new AssertionError("Factory method must not be invoked"); }
	}

	static class ExistingFactory implements FactoryBean<Object> {
		@Override public Object getObject() { throw new AssertionError("Product must not be created"); }
		@Override public Class<?> getObjectType() { return String.class; }
	}

	static class MissingProductBase {
	}

	static class MissingProduct extends MissingProductBase {
	}

	static class MissingProductFactory implements FactoryBean<MissingProduct> {
		MissingProductFactory() { throw new AssertionError("Factory must not be created"); }
		@Override public MissingProduct getObject() { throw new AssertionError("Product must not be created"); }
		@Override public Class<?> getObjectType() { return MissingProduct.class; }
	}

	@SuppressWarnings("rawtypes")
	static class UnknownFactory implements FactoryBean {
		UnknownFactory() { throw new AssertionError("Factory must not be created"); }
		@Override public Object getObject() { throw new AssertionError("Product must not be created"); }
		@Override public Class<?> getObjectType() { return null; }
	}
}
