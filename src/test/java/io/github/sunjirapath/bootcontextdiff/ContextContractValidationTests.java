package io.github.sunjirapath.bootcontextdiff;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextContractValidationTests {

	@TempDir
	Path dir;

	static List<String> invalidBaselines() {
		return List.of(
				"{\"schemaVersion\":1.9,\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":1.0,\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":\"1\",\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":null,\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":true,\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":2147483649,\"beans\":{},\"conditions\":{}}",
				"{\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":[],\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":[]}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":7},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":1.5},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":true},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":{}},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":[]},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":\"true\"}}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":1}}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":0}}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":0.0}}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":\"\"}}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":[]}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":true}}",
				"{\"schemaVersion\":2,\"schemaVersion\":1,\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":\"T\"},\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{\"x\":\"Wrong\",\"x\":\"Right\"},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":false},\"s\":{}}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{\"s\":{\"c\":false,\"c\":true}}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{},}",
				"/*comment*/{\"schemaVersion\":1,\"beans\":{},\"conditions\":{}}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{}} {}",
				"{\"schemaVersion\":1,\"beans\":{},\"conditions\":{}} garbage");
	}

	@ParameterizedTest
	@MethodSource("invalidBaselines")
	void malformedBaselinesAreRejectedWithoutChangingTheirBytes(String invalid) throws Exception {
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			context.refresh();
			Path baseline = dir.resolve("invalid.json");
			Files.writeString(baseline, invalid);
			byte[] before = Files.readAllBytes(baseline);
			assertThatThrownBy(() -> ContextContract.verify(context, baseline)).as(invalid)
				.isInstanceOf(IllegalStateException.class).hasMessageContaining(baseline.toString());
			assertThat(Files.readAllBytes(baseline)).isEqualTo(before);
		}
	}

	@Test
	void invalidUtf8IsRejectedWithoutReplacementOrFileMutation() throws Exception {
		Path baseline = dir.resolve("invalid-utf8.json");
		byte[] bytes = { (byte) 0xC3, (byte) 0x28 };
		Files.write(baseline, bytes);
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			context.refresh();
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.isInstanceOf(UncheckedIOException.class).hasMessageContaining(baseline.toString());
		}
		assertThat(Files.readAllBytes(baseline)).isEqualTo(bytes);
	}

	@Test
	void explicitWriteOverwritesExistingBaselineAndEndsWithOneNewline() throws Exception {
		Path baseline = Files.writeString(dir.resolve("overwrite.json"), "old content".repeat(100));
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			context.refresh();
			ContextContract.write(context, baseline);
			ContextContract.verify(context, baseline);
		}
		assertThat(Files.readString(baseline)).doesNotContain("old content", "\r").endsWith("}\n");
	}

	@ParameterizedTest
	@MethodSource("invalidEncodings")
	void invalidByteSequencesFailWithoutChangingTheFile(byte[] bytes) throws Exception {
		Path baseline = dir.resolve("encoding.json");
		Files.write(baseline, bytes);
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			assertThatThrownBy(() -> ContextContract.verify(context, baseline))
				.isInstanceOfAny(IllegalStateException.class, UncheckedIOException.class)
				.hasMessageContaining(baseline.toString());
		}
		assertThat(Files.readAllBytes(baseline)).isEqualTo(bytes);
	}

	static List<byte[]> invalidEncodings() {
		return List.of(new byte[] { (byte) 0xFE, (byte) 0xFF },
				new byte[] { (byte) 0xFF, 0 }, new byte[] { (byte) 0xFE, 0 },
				new byte[] { (byte) 0xFF });
	}
}
