package io.github.sunjirapath.bootcontextdiff;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

final class Jackson3JsonCodec implements JsonCodec {

	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
		.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
		.withCoercionConfig(LogicalType.Textual, (config) -> config
			.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
		.build();

	// Fixed LF matches the existing baseline writer on every OS.
	private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
		.withObjectIndenter(new DefaultIndenter("  ", "\n"));

	@Override
	public String write(ContextSnapshot snapshot) {
		return MAPPER.writer().with(PRINTER).writeValueAsString(JsonCodec.fields(snapshot)) + "\n";
	}

	@Override
	public ContextSnapshot decode(String json) {
		try {
			var tree = MAPPER.readTree(json);
			if (tree != null && tree.has("checks") && tree.get("checks").isNull()) {
				throw new IllegalArgumentException("Baseline contains null checks");
			}
			return MAPPER.treeToValue(tree, ContextSnapshot.class);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException(ex.getOriginalMessage(), ex);
		}
	}

}
