package org.liquido.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <h1>The frontend's error codes must match {@link LiquidoException.Errors}</h1>
 *
 * The backend throws typed {@link LiquidoException}s and surfaces their codes through the GraphQL
 * error extensions. The frontend branches on those codes via {@code api.err.SOMETHING}, which comes
 * from {@code LiquidoExceptionCodes.js} - a file <b>generated</b> from the enum by
 * {@link org.liquido.tools.LiquidoExceptionJsonGenerator} and then copied into the frontend repo.
 *
 * <h2>Why this test exists</h2>
 *
 * That copy is manual, so it is forgettable, and forgetting is <b>silent</b>. A code that never made
 * it across is simply {@code undefined} in JavaScript, and {@code errCode === undefined} quietly
 * never matches: no error, no warning, the branch is just dead. Exactly the kind of defect that
 * survives all the way into production.
 *
 * It had already happened when this test was written (three codes missing from the frontend, one
 * left over that the backend had dropped). So: encode the invariant, rather than relying on
 * everybody remembering a two-step ritual.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Plain JUnit - no {@code @QuarkusTest}. It reads an enum and a text file; booting Quarkus for
 *       that would just make the suite slower.</li>
 *   <li>Compares the <b>parsed name → code maps</b>, not the file text. A textual diff would be
 *       brittle against pretty-printer formatting and key ordering, and would fail for reasons that
 *       have nothing to do with the codes.</li>
 *   <li><b>Read-only, and never writes into the frontend repo.</b> If the frontend is not checked out
 *       next to the backend (a backend-only clone, or CI), the test <b>skips</b> rather than fails -
 *       the frontend must not become a build dependency.</li>
 * </ul>
 */
public class ErrorCodesInSyncTest {

	/** Relative to the backend module directory, which is the working dir when surefire runs. */
	private static final Path FRONTEND_CODES =
			Path.of("../liquido-mobile-pwa-vue3/src/services/LiquidoExceptionCodes.js");

	/** Matches lines like:   "CANNOT_JOIN_TEAM_INVITE_CODE_INVALID" : 12, */
	private static final Pattern CODE_LINE = Pattern.compile("\"([A-Z_][A-Z0-9_]*)\"\\s*:\\s*(\\d+)");

	private static final String HOW_TO_FIX =
			"\n\n  The frontend's error codes are out of sync with LiquidoException.Errors." +
			"\n  Fix: run org.liquido.tools.LiquidoExceptionJsonGenerator, then copy the generated" +
			"\n       LiquidoExceptionCodes.js to liquido-mobile-pwa-vue3/src/services/\n";

	@Test
	@DisplayName("Generated LiquidoExceptionCodes.js matches the LiquidoException.Errors enum")
	public void frontendErrorCodesMatchTheEnum() throws IOException {
		Assumptions.assumeTrue(Files.exists(FRONTEND_CODES),
				"Frontend repo not checked out next to the backend - skipping. " +
				"This test only runs when " + FRONTEND_CODES + " is present.");

		Map<String, Integer> expected = new LinkedHashMap<>();
		for (LiquidoException.Errors err : LiquidoException.Errors.values()) {
			expected.put(err.name(), err.getLiquidoErrorCode());
		}

		Map<String, Integer> actual = parseCodes(Files.readString(FRONTEND_CODES));

		// Report the two directions separately: "you forgot to regenerate" and "the frontend has a
		// code the backend no longer knows" are different mistakes with different fixes.
		TreeSet<String> missingInFrontend = new TreeSet<>(expected.keySet());
		missingInFrontend.removeAll(actual.keySet());
		TreeSet<String> unknownToBackend = new TreeSet<>(actual.keySet());
		unknownToBackend.removeAll(expected.keySet());

		assertEquals(Set.of(), missingInFrontend,
				"Error codes exist in Java but are missing from the frontend module." + HOW_TO_FIX);
		assertEquals(Set.of(), unknownToBackend,
				"The frontend module has error codes the backend no longer defines." + HOW_TO_FIX);

		// Same names on both sides by now, so any remaining difference is a changed number.
		assertEquals(expected, actual,
				"An error code's NUMBER differs between backend and frontend." + HOW_TO_FIX);
	}

	private static Map<String, Integer> parseCodes(String js) {
		Map<String, Integer> codes = new LinkedHashMap<>();
		Matcher m = CODE_LINE.matcher(js);
		while (m.find()) {
			codes.put(m.group(1), Integer.parseInt(m.group(2)));
		}
		return codes;
	}
}
