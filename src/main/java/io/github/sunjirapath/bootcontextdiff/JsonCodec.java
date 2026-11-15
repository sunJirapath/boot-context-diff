package io.github.sunjirapath.bootcontextdiff;

import java.util.LinkedHashMap;
import java.util.Map;

/** Internal seam: loading the snapshot or contract does not require either Jackson generation. */
interface JsonCodec {

	int MAX_BASELINE_BYTES = 8 * 1024 * 1024;

	String write(ContextSnapshot snapshot);

	ContextSnapshot decode(String json);

	default ContextSnapshot read(String json) {
		if (json.length() > MAX_BASELINE_BYTES) {
			throw new IllegalArgumentException("Baseline exceeds 8 MiB limit");
		}
		ContextSnapshot snapshot = decode(json);
		if (snapshot == null) {
			throw new IllegalArgumentException("Baseline is empty");
		}
		if (snapshot.schemaVersion() != ContextSnapshot.SCHEMA_VERSION
				&& snapshot.schemaVersion() != ContextSnapshot.CHECKS_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Baseline has schemaVersion " + snapshot.schemaVersion()
					+ ", this library reads " + ContextSnapshot.SCHEMA_VERSION + " and " + ContextSnapshot.CHECKS_SCHEMA_VERSION);
		}
		if ((snapshot.schemaVersion() == ContextSnapshot.CHECKS_SCHEMA_VERSION && snapshot.checks().isEmpty())
				|| (snapshot.schemaVersion() == ContextSnapshot.SCHEMA_VERSION && !snapshot.checks().isEmpty())) {
			throw new IllegalArgumentException("Baseline schemaVersion " + snapshot.schemaVersion()
					+ " requires non-empty checks only for schemaVersion " + ContextSnapshot.CHECKS_SCHEMA_VERSION);
		}
		if (snapshot.beans() == null || snapshot.conditions() == null) {
			throw new IllegalArgumentException("Baseline lacks its \"beans\" or \"conditions\" section");
		}
		if (snapshot.beans().containsValue(null) || snapshot.conditions().values()
			.stream().anyMatch((outcomes) -> outcomes == null || outcomes.containsValue(null))) {
			throw new IllegalArgumentException("Baseline contains a null entry");
		}
		return snapshot;
	}

	static JsonCodec select(ClassLoader loader) {
		try {
			Class.forName("tools.jackson.databind.json.JsonMapper", false, loader);
			return new Jackson3JsonCodec();
		}
		catch (ClassNotFoundException absent) {
			try {
				Class.forName("com.fasterxml.jackson.databind.json.JsonMapper", false, loader);
				return new Jackson2JsonCodec();
			}
			catch (ClassNotFoundException alsoAbsent) {
				throw new IllegalStateException("Baseline JSON requires Jackson 3 or Jackson 2 jackson-databind on the consumer classpath", alsoAbsent);
			}
		}
	}

	// Explicit field order and omission keep the public model Jackson-free and schema-1 bytes unchanged.
	static Map<String, Object> fields(ContextSnapshot snapshot) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("schemaVersion", snapshot.schemaVersion());
		fields.put("beans", snapshot.beans());
		fields.put("conditions", snapshot.conditions());
		if (!snapshot.checks().isEmpty()) {
			fields.put("checks", snapshot.checks());
		}
		return fields;
	}

}
