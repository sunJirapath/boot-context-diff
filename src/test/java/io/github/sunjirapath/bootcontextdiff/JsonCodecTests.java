package io.github.sunjirapath.bootcontextdiff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.boot.test.context.FilteredClassLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCodecTests {

	private final List<JsonCodec> codecs = List.of(new Jackson2JsonCodec(), new Jackson3JsonCodec());

	@Test
	void codecsPreserveCommittedSchemaOneBytes() throws Exception {
		String baseline = Files.readString(Path.of("examples/shared/context-baseline.json"));
		for (JsonCodec reader : codecs) {
			ContextSnapshot snapshot = reader.read(baseline);
			for (JsonCodec writer : codecs) {
				assertThat(writer.write(snapshot).getBytes(StandardCharsets.UTF_8))
					.isEqualTo(baseline.getBytes(StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	void codecsProduceIdenticalSchemaTwoBytesAndRoundTripEscapedText() {
		ContextSnapshot snapshot = new ContextSnapshot(2,
				new TreeMap<>(Map.of("a\n\"\\ไทย", "demo.Type", "z", "java.lang.String")),
				Map.of("demo.Source", new TreeMap<>(Map.of("C1", true, "C2", false))),
				Map.of("z", false, "a", true));
		String expected = codecs.get(0).write(snapshot);
		for (JsonCodec codec : codecs) {
			assertThat(codec.write(snapshot).getBytes(StandardCharsets.UTF_8))
				.isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
			assertThat(codec.read(expected)).isEqualTo(snapshot);
		}
		assertThat(expected).startsWith("{\n  \"schemaVersion\" : 2,")
			.contains("\"checks\" : {\n    \"a\" : true,\n    \"z\" : false\n  }").endsWith("}\n");
	}

	@ParameterizedTest
	@MethodSource("malformedInputs")
	void codecsRejectTheSameMalformedInputs(String json) {
		for (JsonCodec codec : codecs) {
			assertThatThrownBy(() -> codec.read(json)).as(codec.getClass().getSimpleName())
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	static Stream<String> malformedInputs() {
		return Stream.concat(ContextContractValidationTests.invalidBaselines().stream(), Stream.concat(
				ContextContractCheckTests.invalidCheckSections().stream()
					.map((section) -> "{\"schemaVersion\":2,\"beans\":{},\"conditions\":{}" + section + "}"),
				Stream.of("", "null", "[]", "{}",
						"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},\"unknown\":true}",
						"{\"schemaVersion\":1,\"beans\":{\"x\":null},\"conditions\":{}}",
						"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":null}}",
						"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":null}}}",
						"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},\"checks\":{\"probe\":true}}")));
	}

	@Test
	void codecsAcceptLongValuesAndRejectDeeplyNestedInput() {
		String json = "{\"schemaVersion\":1,\"beans\":{\"" + "k".repeat(50_000)
				+ "\":\"" + "s".repeat(100_000) + "\"},\"conditions\":{}}";
		for (JsonCodec codec : codecs) {
			assertThat(codec.read(json).beans()).containsEntry("k".repeat(50_000), "s".repeat(100_000));
			assertThatThrownBy(() -> codec.read("[".repeat(1001) + "0" + "]".repeat(1001)))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void classpathSelectionPrefersJacksonThreeAndFallsBackOnlyWhenAbsent() throws Exception {
		assertThat(JsonCodec.select(getClass().getClassLoader())).isInstanceOf(Jackson3JsonCodec.class);
		try (FilteredClassLoader onlyTwo = new FilteredClassLoader("tools.jackson");
				FilteredClassLoader neither = new FilteredClassLoader("tools.jackson", "com.fasterxml.jackson.databind")) {
			assertThat(JsonCodec.select(onlyTwo)).isInstanceOf(Jackson2JsonCodec.class);
			assertThatThrownBy(() -> JsonCodec.select(neither)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("requires Jackson 3 or Jackson 2");
		}
		ClassLoader brokenThree = new ClassLoader(getClass().getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.equals("tools.jackson.databind.json.JsonMapper")) {
					throw new NoClassDefFoundError("broken Jackson 3 dependency");
				}
				return super.loadClass(name, resolve);
			}
		};
		assertThatThrownBy(() -> JsonCodec.select(brokenThree)).isInstanceOf(NoClassDefFoundError.class);
	}

}
