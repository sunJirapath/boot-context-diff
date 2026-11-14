plugins {
	java
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	exclusiveContent {
		forRepository {
			maven {
				url = uri(providers.gradleProperty("contextContractRepository").get())
			}
		}
		filter {
			includeGroup("io.github.sunjirapath.bootcontextdiff")
		}
	}
	mavenCentral()
}

dependencies {
	implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.9"))
	implementation("org.springframework.boot:spring-boot-starter")
	testImplementation("io.github.sunjirapath.bootcontextdiff:boot-context-diff:0.1.0")
	testImplementation("tools.jackson.core:jackson-databind")
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
	systemProperty("context.baseline", layout.projectDirectory.file("../shared/context-baseline.json").asFile.absolutePath)
	// the example test reads this to decide whether to write the baseline
	environment("UPDATE_CONTEXT_BASELINE", System.getenv("UPDATE_CONTEXT_BASELINE") ?: "")
}
