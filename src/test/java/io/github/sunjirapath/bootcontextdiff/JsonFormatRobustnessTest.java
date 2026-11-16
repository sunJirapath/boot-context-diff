package io.github.sunjirapath.bootcontextdiff;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFormatRobustnessTest {

	@TempDir
	Path dir;

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

	@Test
	void baselineFileEncodingRemainsUtf8() throws Exception {
		Path baseline = dir.resolve("unicode.json");
		try (var context = new GenericApplicationContext()) {
			context.registerBeanDefinition("บริการ-😀", new RootBeanDefinition(String.class));
			ContextContract.write(context, baseline);
			ContextContract.verify(context, baseline);
		}
		assertThat(new String(Files.readAllBytes(baseline), StandardCharsets.UTF_8)).contains("บริการ-😀");
	}

}
