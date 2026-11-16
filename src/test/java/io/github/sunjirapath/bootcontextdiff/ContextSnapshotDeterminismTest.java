package io.github.sunjirapath.bootcontextdiff;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSnapshotDeterminismTest {

	@Test
	void outputIsIndependentOfInputMapInsertionOrder() {
		Map<String, String> beansForward = new LinkedHashMap<>();
		beansForward.put("zeta", "java.lang.String");
		beansForward.put("alpha", "java.lang.Integer");
		Map<String, String> beansReverse = new LinkedHashMap<>();
		beansReverse.put("alpha", "java.lang.Integer");
		beansReverse.put("zeta", "java.lang.String");
		Map<String, Boolean> outcomesForward = new LinkedHashMap<>();
		outcomesForward.put("ZCondition", false);
		outcomesForward.put("ACondition", true);
		Map<String, Boolean> outcomesReverse = new LinkedHashMap<>();
		outcomesReverse.put("ACondition", true);
		outcomesReverse.put("ZCondition", false);
		Map<String, Map<String, Boolean>> conditionsForward = new LinkedHashMap<>();
		conditionsForward.put("z.Source", outcomesForward);
		conditionsForward.put("a.Source", Map.of("Condition", true));
		Map<String, Map<String, Boolean>> conditionsReverse = new LinkedHashMap<>();
		conditionsReverse.put("a.Source", Map.of("Condition", true));
		conditionsReverse.put("z.Source", outcomesReverse);

		ContextSnapshot first = new ContextSnapshot(1, beansForward, conditionsForward);
		ContextSnapshot second = new ContextSnapshot(1, beansReverse, conditionsReverse);

		// FINDING: ContextSnapshot preserves caller map iteration order, so equivalent snapshots serialize differently.
		JsonCodec codec = JsonCodec.select(getClass().getClassLoader());
		assertThat(codec.write(first)).isEqualTo(codec.write(second));
	}

	@Test
	void localeAndTimezoneDoNotChangeUtf8BaselineBytes() {
		Locale oldLocale = Locale.getDefault();
		TimeZone oldTimezone = TimeZone.getDefault();
		ContextSnapshot snapshot = new ContextSnapshot(1, Map.of("I", "java.lang.String"), Map.of());
		try {
			Locale.setDefault(Locale.US);
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			JsonCodec codec = JsonCodec.select(getClass().getClassLoader());
			byte[] expected = codec.write(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8);
			Locale.setDefault(Locale.forLanguageTag("th-TH"));
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
			assertThat(codec.write(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.isEqualTo(expected);
		}
		finally {
			Locale.setDefault(oldLocale);
			TimeZone.setDefault(oldTimezone);
		}
	}

}
