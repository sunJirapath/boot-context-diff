package io.github.sunjirapath.bootcontextdiff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;

final class Jackson2JsonCodec implements JsonCodec {

	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		// Jackson 3 enables this by default; the malformed-input parity cases cover trailing content.
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
		.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
		.withCoercionConfig(LogicalType.Textual, (config) -> config
			.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
		.build();

	private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
		.withObjectIndenter(new DefaultIndenter("  ", "\n"));

	@Override
	public String write(ContextSnapshot snapshot) {
		try {
			return MAPPER.writer().with(PRINTER).writeValueAsString(JsonCodec.fields(snapshot)) + "\n";
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Cannot serialize context baseline", ex);
		}
	}

	@Override
	public ContextSnapshot decode(String json) {
		try {
			return MAPPER.readValue(json, ContextSnapshot.class);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException(ex.getOriginalMessage(), ex);
		}
	}

}
