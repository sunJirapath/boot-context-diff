package demo;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.bootcontextdiff.ContextContract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ContextContractExampleTest {

	// Tests run with the example's directory as working directory, so this is examples/shared/.
	private static final Path BASELINE = Path.of("../shared/context-baseline.json");

	@Autowired
	ConfigurableApplicationContext context;

	@BeforeEach
	void generateBaselineOnlyWhenAskedTo() {
		// Baseline generation is explicit: UPDATE_CONTEXT_BASELINE=true ./mvnw test (or ./gradlew test)
		if ("true".equals(System.getenv("UPDATE_CONTEXT_BASELINE"))) {
			ContextContract.write(this.context, BASELINE);
		}
	}

	@Test
	void unchangedContextMatchesBaseline() {
		ContextContract.verify(this.context, BASELINE);
	}

	@Test
	void meaningfulChangeFailsVerifyAndKeepsBaseline() throws Exception {
		byte[] before = Files.readAllBytes(BASELINE);
		try (ConfigurableApplicationContext changed = new SpringApplicationBuilder(DemoApplication.class)
			.web(WebApplicationType.NONE)
			.properties("demo.greeting.enabled=false")
			.run()) {
			assertThatThrownBy(() -> ContextContract.verify(changed, BASELINE)).isInstanceOf(AssertionError.class)
				.hasMessageContaining("REMOVED BEAN      greetingService")
				.hasMessageContaining("CONDITION CHANGED");
		}
		assertThat(Files.readAllBytes(BASELINE)).isEqualTo(before);
	}

	@Test
	void outputIsDeterministic(@TempDir Path dir) throws Exception {
		Path again = dir.resolve("again.json");
		ContextContract.write(this.context, again);
		assertThat(Files.readAllBytes(again)).isEqualTo(Files.readAllBytes(BASELINE));
	}

}
