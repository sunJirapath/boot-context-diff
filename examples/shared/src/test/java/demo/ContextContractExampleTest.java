package demo;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.sunjirapath.bootcontextdiff.ContextContract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ContextContractExampleTest {

	private static final Path BASELINE = Path.of(System.getProperty("context.baseline", ""));

	@Autowired
	ConfigurableApplicationContext context;

	@BeforeEach
	void generateBaselineOnlyWhenAskedTo() {
		assertThat(BASELINE.isAbsolute()).as("Set -Dcontext.baseline to the absolute baseline path (also in IDEs)")
			.isTrue();
		// See README Runnable examples for the wrapper and isolated repository arguments.
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
