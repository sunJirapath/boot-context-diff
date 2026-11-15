package io.github.sunjirapath.bootcontextdiff;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCodecSecurityTests {

	private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

	static Stream<String> hostileJson() {
		return ContextContractSecurityTests.hostileBaselines().filter((json) -> !json.contains("\"?\""));
	}

	@ParameterizedTest
	@MethodSource("hostileJson")
	@Timeout(10)
	void duplicateUnknownWrongTypeNullTruncationAndDeepNestingAttacksFailInBothCodecs(String json) {
		// Kills relaxing duplicate detection, unknown-field/type/null validation, or trailing-token checks in either mapper.
		for (JsonCodec codec : List.of(new Jackson2JsonCodec(), new Jackson3JsonCodec())) {
			assertThatThrownBy(() -> codec.read(json)).as(codec.getClass().getSimpleName())
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	@Timeout(10)
	void oversizedWhitespaceAttackCannotBypassEitherCodecLimit() {
		// Kills the shared size guard: the underlying mappers would accept this otherwise-valid empty snapshot.
		String json = " ".repeat(JsonCodec.MAX_BASELINE_BYTES) + "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{}}";
		for (JsonCodec codec : List.of(new Jackson2JsonCodec(), new Jackson3JsonCodec())) {
			assertThatThrownBy(() -> codec.read(json)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("8 MiB");
		}
	}

	@Test
	void schemaOneNullChecksAttackCannotPassAsAbsent() {
		// Kills either codec's explicit-null guard: the record constructor otherwise normalizes null to an empty map.
		String valid = "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{}}";
		String attack = "{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},\"checks\":null}";
		for (JsonCodec codec : List.of(new Jackson2JsonCodec(), new Jackson3JsonCodec())) {
			assertThat(codec.read(valid).checks()).isEmpty();
			assertThatThrownBy(() -> codec.read(attack)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("null checks");
		}
	}

	@Test
	void polymorphicDeserializationAttackNeverConstructsNamedClasses() {
		// Kills interpreting a type-name string or @class metadata as an instantiable runtime object.
		String type = Gadget.class.getName();
		for (JsonCodec codec : List.of(new Jackson2JsonCodec(), new Jackson3JsonCodec())) {
			String json = codec.write(new ContextSnapshot(1, Map.of("literal", type), Map.of()));
			assertThat(codec.read(json).beans()).containsEntry("literal", type);
			assertThatThrownBy(() -> codec.read(json.replace("{", "{\"@class\":\"" + type + "\", ")))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(CONSTRUCTIONS.get()).isZero();
	}

	public static final class Gadget {
		public Gadget() {
			CONSTRUCTIONS.incrementAndGet();
		}
	}

}
