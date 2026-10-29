plugins {
	java
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenLocal() // the library is installed there by `./mvnw install` in the repository root
	mavenCentral()
}

dependencies {
	implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.9"))
	implementation("org.springframework.boot:spring-boot-starter")
	testImplementation("io.github.sunjirapath.bootcontextdiff:boot-context-diff:0.1.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// the fixture is shared with the Maven and Groovy DSL examples
sourceSets {
	main { java.setSrcDirs(listOf("../shared/src/main/java")) }
	test { java.setSrcDirs(listOf("../shared/src/test/java")) }
}

tasks.test {
	useJUnitPlatform()
	// the example test reads this to decide whether to write the baseline
	environment("UPDATE_CONTEXT_BASELINE", System.getenv("UPDATE_CONTEXT_BASELINE") ?: "")
}
