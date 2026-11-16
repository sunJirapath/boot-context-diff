package io.github.sunjirapath.bootcontextdiff;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Needs both Jackson generations, so the Boot 3 (Jackson 2 only) consumer excludes it like JsonCodecTests. */
class JsonCodecUnicodeParityTest {

	@Test
	void unicodeIncludingThaiEmojiAndRightToLeftTextRoundTripsWithMixedLineEndings() {
		String text = "ภาษาไทย 😀 𐐷 مرحبا שלום";
		ContextSnapshot expected = new ContextSnapshot(1, Map.of("บริการ-😀", text),
				Map.of("แหล่งข้อมูล", Map.of("شرط", true)));
		for (JsonCodec codec : java.util.List.of(new Jackson2JsonCodec(), new Jackson3JsonCodec())) {
			String json = codec.write(expected);
			String mixedEndings = json.replace("\n", "\r\n").replace("\r\n  ", "\n  ");
			assertThat(codec.read(mixedEndings)).isEqualTo(expected);
			assertThat(codec.write(codec.read(mixedEndings)).getBytes(StandardCharsets.UTF_8))
				.isEqualTo(json.getBytes(StandardCharsets.UTF_8));
		}
	}

}
