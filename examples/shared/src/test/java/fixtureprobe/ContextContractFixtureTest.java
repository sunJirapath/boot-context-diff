package fixtureprobe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.sunjirapath.bootcontextdiff.ContextContract;
import io.github.sunjirapath.bootcontextdiff.ContextSnapshot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ContextContractFixtureTest.GreetingConfiguration.class)
class ContextContractFixtureTest {

	private static final String ORIGINAL_ENDPOINT = "http://127.0.0.1:49152";

	private static final AtomicReference<String> ENDPOINT = new AtomicReference<>(ORIGINAL_ENDPOINT);

	@Autowired
	ConfigurableApplicationContext context;

	@MockitoBean(name = "fixtureGreeting", enforceOverride = true)
	Greeting greeting;

	@TempDir
	Path dir;

	// FINDING: No real container or Boot web/data slice is exercised; see README Fixture coverage.
	@DynamicPropertySource
	static void dynamicEndpoint(DynamicPropertyRegistry registry) {
		registry.add("fixture.endpoint", ENDPOINT::get);
	}

	@Test
	void mocksAndDynamicEndpointsNeedExplicitChecks() throws Exception {
		Path structure = dir.resolve("structure.json");
		Path checked = dir.resolve("checked.json");
		when(greeting.greet()).thenReturn("mock greeting");
		assertThat(ContextSnapshot.of(context).beans()).containsKey("fixtureGreeting");
		ContextContract.write(context, structure);
		ContextContract.write(context, checked, checks());
		byte[] before = Files.readAllBytes(checked);
		try {
			ENDPOINT.set("http://127.0.0.1:49153");
			when(greeting.greet()).thenReturn("changed greeting");
			assertThat(context.getEnvironment().getProperty("fixture.endpoint")).isEqualTo(ENDPOINT.get());
			ContextContract.verify(context, structure);
			assertThatThrownBy(() -> ContextContract.verify(context, checked, checks()))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("CHECK CHANGED     endpoint expected: true -> false")
				.hasMessageContaining("CHECK CHANGED     greeting responds: true -> false");
			assertThat(Files.readAllBytes(checked)).isEqualTo(before);
			assertThat(Files.readString(checked)).doesNotContain(ORIGINAL_ENDPOINT, ENDPOINT.get(), "mock greeting");
		}
		finally {
			ENDPOINT.set(ORIGINAL_ENDPOINT);
		}
	}

	@Test
	void selectedConfigurationsRequireSeparateBaselines() {
		Path full = dir.resolve("full.json");
		Path subset = dir.resolve("subset.json");
		new ApplicationContextRunner().withUserConfiguration(FullConfiguration.class)
			.run((ctx) -> ContextContract.write(ctx, full));
		new ApplicationContextRunner().withUserConfiguration(GreetingConfiguration.class).run((ctx) -> {
			assertThatThrownBy(() -> ContextContract.verify(ctx, full))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("REMOVED BEAN      fixtureStorage");
			ContextContract.write(ctx, subset);
			ContextContract.verify(ctx, subset);
		});
	}

	private Map<String, Boolean> checks() {
		return Map.of("endpoint expected", ORIGINAL_ENDPOINT.equals(context.getEnvironment().getProperty("fixture.endpoint")),
				"greeting responds", "mock greeting".equals(greeting.greet()));
	}

	interface Greeting {

		String greet();

	}

	@Configuration(proxyBeanMethods = false)
	static class GreetingConfiguration {

		@Bean
		Greeting fixtureGreeting() {
			return () -> "production greeting";
		}

	}

	@Configuration(proxyBeanMethods = false)
	@Import(GreetingConfiguration.class)
	static class FullConfiguration {

		@Bean
		String fixtureStorage() {
			return "storage";
		}

	}

}
