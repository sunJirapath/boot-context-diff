package io.github.sunjirapath.bootcontextdiff;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > JsonCodec.MAX_BASELINE_BYTES) {
			throw new IllegalArgumentException("Baseline exceeds 8 MiB limit");
		}
		Path temporary = null;
		try {
			rejectSymlinks(baseline);
			Path target = baseline.toAbsolutePath().normalize();
			if (target.getParent() == null) {
				throw new IOException("Baseline path is a directory, not a file");
			}
			Files.createDirectories(target.getParent());
			rejectSymlinks(target);
			temporary = Files.createTempFile(target.getParent(), ".context-baseline-", ".tmp");
			Files.write(temporary, bytes);
			// No non-atomic fallback: an interrupted write must preserve the previous baseline.
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot write baseline " + DiagnosticText.of(baseline),
					new IOException(DiagnosticText.of(ex.getMessage())));
		}
		finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				}
				catch (IOException ex) {
					throw new UncheckedIOException("Cannot remove temporary baseline", new IOException(DiagnosticText.of(ex.getMessage())));
				}
			}
		}
	}

	/**
	 * Compares {@code context} with the baseline.
	 * @throws AssertionError if the baseline is missing, invalid or differs; the baseline is left untouched
	 * @throws IllegalStateException if live types are unresolved or condition/alias identities collide
	 */
	public static void verify(ConfigurableApplicationContext context, Path baseline) {
		verify(context, baseline, Map.of());
	}

	/** Verifies structure and supplied checks; incomplete type, condition or alias analysis fails closed. */
	public static void verify(ConfigurableApplicationContext context, Path baseline, Map<String, Boolean> checks) {
		ContextSnapshot expected = read(baseline);
		ContextSnapshot actual = ContextSnapshot.of(context, checks);
		if (actual.beans().containsValue("?")) {
			throw new IllegalStateException("Incomplete bean type analysis for baseline " + DiagnosticText.of(baseline.toAbsolutePath())
					+ ": unresolved type '?'. Supply declared type metadata and regenerate deliberately.");
		}
		List<String> differences = expected.diff(actual);
		if (!differences.isEmpty()) {
			throw new AssertionError("ApplicationContext differs from baseline " + DiagnosticText.of(baseline.toAbsolutePath())
					+ " (" + differences.size() + " difference(s)):\n"
					+ String.join("\n", differences.subList(0, Math.min(100, differences.size())))
					+ (differences.size() > 100 ? "\n...[diff truncated; first 100 differences shown]" : "")
					+ "\nA difference is not necessarily a bug (dependency upgrades and config changes do this). "
					+ "If it is intended, review it and regenerate the baseline with ContextContract.write(context, path).");
		}
	}

	private static ContextSnapshot read(Path baseline) {
		try {
			rejectSymlinks(baseline);
			if (Files.isDirectory(baseline, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalArgumentException("Baseline is a directory, not a file");
			}
			if (!Files.isRegularFile(baseline, LinkOption.NOFOLLOW_LINKS)) {
				throw new IllegalArgumentException("No context baseline. Generate it deliberately with ContextContract.write(context, path) and commit it.");
			}
			byte[] bytes;
			try (InputStream input = Files.newInputStream(baseline, LinkOption.NOFOLLOW_LINKS)) {
				bytes = input.readNBytes(JsonCodec.MAX_BASELINE_BYTES + 1);
			}
			if (bytes.length > JsonCodec.MAX_BASELINE_BYTES) {
				throw new IllegalArgumentException("Baseline exceeds 8 MiB limit");
			}
			if (bytes.length > 1 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE)
					|| (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF))) {
				// PowerShell 5 `>` writes UTF-16LE with a BOM
				throw new IllegalArgumentException("Baseline is UTF-16; re-save it as UTF-8");
			}
			// strict decode: invalid UTF-8 must fail here, not become U+FFFD and a confusing diff
			String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			// Windows editors (Notepad, PowerShell redirects) prepend a BOM that Jackson rejects
			ContextSnapshot snapshot = CodecHolder.CODEC.read(json.startsWith("\uFEFF") ? json.substring(1) : json);
			if (snapshot.beans().containsValue("?")) {
				throw new IllegalArgumentException("Incomplete bean type analysis: unresolved type '?'");
			}
			return snapshot;
		}
		catch (IOException | IllegalArgumentException | SecurityException ex) {
			// Do not attach parser causes: their messages may contain unescaped baseline content.
			throw new AssertionError("Baseline " + DiagnosticText.of(baseline.toAbsolutePath())
					+ " is not a valid context baseline: " + DiagnosticText.of(ex.getMessage()));
		}
	}

	private static void rejectSymlinks(Path path) throws IOException {
		Path absolute = path.toAbsolutePath();
		Path current = absolute.getRoot();
		for (Path component : absolute) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Symbolic links are not allowed in baseline paths");
			}
			if (!current.equals(absolute) && Files.exists(current, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Baseline parent is not a directory");
			}
		}
	}

}
