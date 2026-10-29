package io.github.sunjirapath.bootcontextdiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

import org.springframework.context.ConfigurableApplicationContext;

/**
 * Records and verifies the structure of an application context against a baseline file.
 * Baselines are only ever written by {@link #write}; {@link #verify} never touches the file.
 */
public final class ContextContract {

	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
		.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
		.withCoercionConfig(LogicalType.Textual, (config) -> config
			.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
		.build();

	// Fixed "\n" so a baseline written on Windows is byte-identical to one written on Linux.
	private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
		.withObjectIndenter(new DefaultIndenter("  ", "\n"));

	private ContextContract() {
	}

	/** Writes (or overwrites) the baseline for {@code context}. Call this deliberately, never from verify. */
	public static void write(ConfigurableApplicationContext context, Path baseline) {
		String json = MAPPER.writer().with(PRINTER).writeValueAsString(ContextSnapshot.of(context)) + "\n";
		try {
			if (baseline.toAbsolutePath().getParent() != null) {
				Files.createDirectories(baseline.toAbsolutePath().getParent());
			}
			Files.writeString(baseline, json, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot write baseline " + baseline, ex);
		}
	}

	/**
	 * Compares {@code context} with the baseline.
	 * @throws AssertionError if the baseline is missing or differs; the baseline is left untouched
	 */
	public static void verify(ConfigurableApplicationContext context, Path baseline) {
		if (Files.isDirectory(baseline)) {
			throw new AssertionError("Baseline path " + baseline.toAbsolutePath() + " is a directory, not a file");
		}
		if (!Files.isRegularFile(baseline)) {
			throw new AssertionError("No context baseline at " + baseline.toAbsolutePath()
					+ ". Generate it deliberately with ContextContract.write(context, path) and commit it.");
		}
		ContextSnapshot expected = read(baseline);
		List<String> differences = expected.diff(ContextSnapshot.of(context));
		if (!differences.isEmpty()) {
			throw new AssertionError("ApplicationContext differs from baseline " + baseline.toAbsolutePath()
					+ " (" + differences.size() + " difference(s)):\n" + String.join("\n", differences)
					+ "\nA difference is not necessarily a bug (dependency upgrades and config changes do this). "
					+ "If it is intended, review it and regenerate the baseline with ContextContract.write(context, path).");
		}
	}

	private static ContextSnapshot read(Path baseline) {
		ContextSnapshot snapshot;
		try {
			byte[] bytes = Files.readAllBytes(baseline);
			if (bytes.length > 1 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
					|| (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF))) {
				// PowerShell 5 `>` writes UTF-16LE with a BOM
				throw new IllegalStateException("Baseline " + baseline + " is UTF-16; re-save it as UTF-8");
			}
			// strict decode: invalid UTF-8 must fail here, not become U+FFFD and a confusing diff
			String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			// Windows editors (Notepad, PowerShell redirects) prepend a BOM that Jackson rejects
			snapshot = MAPPER.readValue(json.startsWith("﻿") ? json.substring(1) : json, ContextSnapshot.class);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read baseline " + baseline, ex);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Baseline " + baseline + " is not a valid context baseline: "
					+ ex.getOriginalMessage(), ex);
		}
		if (snapshot == null) {
			throw new IllegalStateException("Baseline " + baseline + " is empty");
		}
		if (snapshot.schemaVersion() != ContextSnapshot.SCHEMA_VERSION) {
			throw new IllegalStateException("Baseline " + baseline + " has schemaVersion " + snapshot.schemaVersion()
					+ ", this library reads " + ContextSnapshot.SCHEMA_VERSION);
		}
		if (snapshot.beans() == null || snapshot.conditions() == null) {
			throw new IllegalStateException("Baseline " + baseline + " lacks its \"beans\" or \"conditions\" section");
		}
		if (snapshot.beans().containsValue(null) || snapshot.conditions().values()
			.stream().anyMatch((outcomes) -> outcomes == null || outcomes.containsValue(null))) {
			throw new IllegalStateException("Baseline " + baseline + " contains a null entry");
		}
		return snapshot;
	}

}
