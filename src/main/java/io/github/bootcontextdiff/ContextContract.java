package io.github.bootcontextdiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * Records and verifies the structure of an application context against a baseline file.
 * Baselines are only ever written by {@link #write}; {@link #verify} never touches the file.
 */
public final class ContextContract {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
			snapshot = MAPPER.readValue(Files.readString(baseline, StandardCharsets.UTF_8), ContextSnapshot.class);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read baseline " + baseline, ex);
		}
		if (snapshot.schemaVersion() != ContextSnapshot.SCHEMA_VERSION) {
			throw new IllegalStateException("Baseline " + baseline + " has schemaVersion " + snapshot.schemaVersion()
					+ ", this library reads " + ContextSnapshot.SCHEMA_VERSION);
		}
		return snapshot;
	}

}
