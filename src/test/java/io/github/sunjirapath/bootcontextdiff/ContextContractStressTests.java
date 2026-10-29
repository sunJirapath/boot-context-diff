package io.github.sunjirapath.bootcontextdiff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextContractStressTests {

	@TempDir
	Path dir;

	@Test
	void concurrentIndependentContractsHaveIdenticalBytesAndDoNotSharePrinterState() throws Exception {
		try (var context = new GenericApplicationContext(); var executor = Executors.newFixedThreadPool(8)) {
			context.registerBean("service", String.class, () -> "private-value");
			context.refresh();
			Path reference = dir.resolve("reference.json");
			ContextContract.write(context, reference);
			byte[] expected = Files.readAllBytes(reference);
			List<Callable<Void>> tasks = new ArrayList<>();
			for (int worker = 0; worker < 16; worker++) {
				Path baseline = dir.resolve("worker-" + worker + ".json");
				tasks.add(() -> {
					for (int iteration = 0; iteration < 20; iteration++) {
						ContextContract.write(context, baseline);
						ContextContract.verify(context, baseline);
						assertThat(Files.readAllBytes(baseline)).isEqualTo(expected);
					}
					return null;
				});
			}
			for (var result : executor.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
				result.get();
			}
		}
	}

	@Test
	void thousandsOfLazyDefinitionsAreOrderIndependentAndASingleChangeIsVisible() throws Exception {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 2_000; i++) {
			names.add("service-" + i);
		}
		Path baseline = dir.resolve("large.json");
		try (var original = lazyContext(names)) {
			ContextContract.write(original, baseline);
			assertThat(original.getBeanFactory().getSingletonNames()).isEmpty();
		}
		Collections.shuffle(names, new Random(20261029));
		try (var reordered = lazyContext(names)) {
			Path actual = dir.resolve("reordered.json");
			ContextContract.write(reordered, actual);
			ContextContract.verify(reordered, baseline);
			assertThat(Files.readAllBytes(actual)).isEqualTo(Files.readAllBytes(baseline));
			reordered.registerBeanDefinition("service-1999", new RootBeanDefinition(Integer.class));
			assertThatThrownBy(() -> ContextContract.verify(reordered, baseline))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("(1 difference(s))")
				.hasMessageContaining("BEAN TYPE CHANGED service-1999: java.lang.String -> java.lang.Integer");
			assertThat(reordered.getBeanFactory().getSingletonNames()).isEmpty();
		}
	}

	private static GenericApplicationContext lazyContext(List<String> names) {
		var context = new GenericApplicationContext();
		for (String name : names) {
			RootBeanDefinition definition = new RootBeanDefinition(String.class);
			definition.setLazyInit(true);
			definition.setInstanceSupplier(() -> { throw new AssertionError("Snapshot instantiated " + name); });
			context.registerBeanDefinition(name, definition);
		}
		return context;
	}
}
