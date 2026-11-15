package io.github.sunjirapath.bootcontextdiff;

/** Escapes untrusted identifiers for single-line CI diagnostics; never changes comparison keys. */
final class DiagnosticText {

	private DiagnosticText() {
	}

	static String of(Object value) {
		String text = String.valueOf(value);
		StringBuilder safe = new StringBuilder();
		int limit = Math.min(text.length(), 256);
		for (int i = 0; i < limit; i++) {
			char c = text.charAt(i);
			if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
					|| c == '\u2028' || c == '\u2029' || "<>`[]*_|".indexOf(c) >= 0
					|| (c == ':' && ((i > 0 && text.charAt(i - 1) == ':')
							|| (i + 1 < text.length() && text.charAt(i + 1) == ':')))) {
				String hex = Integer.toHexString(c);
				safe.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
			}
			else {
				safe.append(c);
			}
		}
		if (text.length() > limit) {
			safe.append("...[truncated]");
		}
		return safe.toString();
	}

}
