package io.github.sunjirapath.bootcontextdiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * Records and verifies context structure and optional named boolean checks against a baseline file.
 * Baselines are only ever written by {@link #write}; {@link #verify} never touches the file.
 */
public final class ContextContract {

	private static final class CodecHolder {
		private static final JsonCodec CODEC = JsonCodec.select(JsonCodec.class.getClassLoader());
	}

	private ContextContract() {
	}

	/** Writes (or overwrites) the baseline for {@code context}. Call this deliberately, never from verify. */
	public static void write(ConfigurableApplicationContext context, Path baseline) {
		write(context, baseline, Map.of());
	}

	/** Writes structure plus named boolean checks evaluated by the caller, such as feature flags or HTTP probes. */
	public static void write(ConfigurableApplicationContext context, Path baseline, Map<String, Boolean> checks) {
		String json = CodecHolder.CODEC.write(ContextSnapshot.of(context, checks));
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
	 * @throws IllegalStateException if types are unresolved or condition/alias identities collide
	 */
	public static void verify(ConfigurableApplicationContext context, Path baseline) {
		verify(context, baseline, Map.of());
	}

	/** Verifies structure and supplied checks; incomplete type, condition or alias analysis fails closed. */
	public static void verify(ConfigurableApplicationContext context, Path baseline, Map<String, Boolean> checks) {
		if (Files.isDirectory(baseline)) {
			throw new AssertionError("Baseline path " + baseline.toAbsolutePath() + " is a directory, not a file");
		}
		if (!Files.isRegularFile(baseline)) {
			throw new AssertionError("No context baseline at " + baseline.toAbsolutePath()
					+ ". Generate it deliberately with ContextContract.write(context, path) and commit it.");
		}
		ContextSnapshot expected = read(baseline);
		ContextSnapshot actual = ContextSnapshot.of(context, checks);
		if (expected.beans().containsValue("?") || actual.beans().containsValue("?")) {
			throw new IllegalStateException("Incomplete bean type analysis for baseline " + baseline.toAbsolutePath()
					+ ": unresolved type '?'. Supply declared type metadata and regenerate deliberately.");
		}
		List<String> differences = expected.diff(actual);
		if (!differences.isEmpty()) {
			// FINDING: No diff output-path API; retain full assertion output in test reports (README Review artifacts).
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
			snapshot = CodecHolder.CODEC.read(json.startsWith("﻿") ? json.substring(1) : json);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read baseline " + baseline, ex);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Baseline " + baseline + " is not a valid context baseline: "
					+ ex.getMessage(), ex);
		}
		return snapshot;
	}

}
