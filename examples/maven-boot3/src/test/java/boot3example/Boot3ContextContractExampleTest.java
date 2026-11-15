package boot3example;

import java.nio.file.Files;
import java.nio.file.Path;

import demo.DemoApplication;
import io.github.sunjirapath.bootcontextdiff.ContextContract;
import io.github.sunjirapath.bootcontextdiff.ContextSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.SpringVersion;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Boot3ContextContractExampleTest {

	private static final Path BASELINE = Path.of(System.getProperty("context.baseline", ""));

	@BeforeEach
	void requireOwnedBaselineAndPinnedRuntime() {
		assertThat(BASELINE.isAbsolute()).as("Set -Dcontext.baseline to the absolute baseline path").isTrue();
		assertThat(SpringBootVersion.getVersion()).isEqualTo("3.5.16");
		assertThat(SpringVersion.getVersion()).startsWith("6.");
		assertThat(ClassUtils.isPresent("com.fasterxml.jackson.databind.json.JsonMapper", getClass().getClassLoader())).isTrue();
		assertThat(ClassUtils.isPresent("tools.jackson.databind.json.JsonMapper", getClass().getClassLoader())).isFalse();
		if ("true".equals(System.getenv("UPDATE_CONTEXT_BASELINE"))) {
			try (GenericApplicationContext context = contractContext(true)) {
				ContextContract.write(context, BASELINE);
			}
		}
	}

	@Test
	void unchangedContextMatchesCommittedBaselineAndBytes(@TempDir Path dir) throws Exception {
		try (GenericApplicationContext context = contractContext(true)) {
			ContextContract.verify(context, BASELINE);
			Path again = dir.resolve("again.json");
			ContextContract.write(context, again);
			assertThat(Files.readAllBytes(again)).isEqualTo(Files.readAllBytes(BASELINE));
		}
	}

	@Test
	void meaningfulChangeFailsVerifyAndKeepsCommittedBaseline() throws Exception {
		byte[] before = Files.readAllBytes(BASELINE);
		try (GenericApplicationContext changed = contractContext(false)) {
			assertThatThrownBy(() -> ContextContract.verify(changed, BASELINE)).isInstanceOf(AssertionError.class)
				.hasMessageContaining("REMOVED BEAN      greetingService");
		}
		assertThat(Files.readAllBytes(BASELINE)).isEqualTo(before);
	}

	@Test
	void bootAutoConfigurationChangeFailsAgainstIndependentCapture(@TempDir Path dir) throws Exception {
		Path baseline = dir.resolve("boot-context.json");
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(DemoApplication.class);
		runner.run((ctx) -> {
			assertThat(ContextSnapshot.of(ctx).conditions()).containsKey("demo.DemoApplication#greetingService");
			ContextContract.write(ctx, baseline);
		});
		byte[] before = Files.readAllBytes(baseline);
		runner.run((ctx) -> ContextContract.verify(ctx, baseline));
		runner.withPropertyValues("demo.greeting.enabled=false").run((ctx) ->
			assertThatThrownBy(() -> ContextContract.verify(ctx, baseline)).isInstanceOf(AssertionError.class)
				.hasMessageContaining("REMOVED BEAN      greetingService").hasMessageContaining("CONDITION CHANGED"));
		assertThat(Files.readAllBytes(baseline)).isEqualTo(before);
	}

	private GenericApplicationContext contractContext(boolean greeting) {
		GenericApplicationContext context = new GenericApplicationContext();
		if (greeting) {
			context.registerBean("greetingService", DemoApplication.GreetingService.class);
		}
		context.refresh();
		return context;
	}

}
