package demo;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Tiny fixture shared by the Maven, Gradle Groovy and Gradle Kotlin examples. */
@SpringBootApplication
public class DemoApplication {

	@Bean
	@ConditionalOnProperty(name = "demo.greeting.enabled", matchIfMissing = true)
	GreetingService greetingService() {
		return new GreetingService();
	}

	public static class GreetingService {

		public String greet() {
			return "hello";
		}

	}

}
