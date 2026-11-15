package classpathprobe;

import org.junit.jupiter.api.Test;
import io.github.sunjirapath.bootcontextdiff.ContextSnapshot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class VerifierClasspathInfluenceTest {

	@Test
	void jacksonOnTheTestClasspathCanEnableAnApplicationBean() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(JacksonDependentConfiguration.class);
		runner.run((ctx) -> assertThat(ContextSnapshot.of(ctx).beans())
			.containsEntry("jacksonPresent", "java.lang.String"));
		runner.withClassLoader(new FilteredClassLoader("tools.jackson"))
			.run((ctx) -> assertThat(ContextSnapshot.of(ctx).beans()).doesNotContainKey("jacksonPresent"));
	}

	@Configuration(proxyBeanMethods = false)
	static class JacksonDependentConfiguration {

		@Bean
		@ConditionalOnClass(name = "tools.jackson.databind.json.JsonMapper")
		String jacksonPresent() {
			return "present";
		}

	}

}
