package org.liquido.polly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two key derivations Polly's whole privacy story rests on.
 *
 * <p>Plain JUnit against the static methods - the arithmetic needs no CDI and no database.
 */
public class PollyKeysTest {

	private static final String SECRET = "aTestOnlyPollyHmacSecret";
	private static final String CREDENTIAL = "credential-id-AAAA";
	private static final String OTHER_CREDENTIAL = "credential-id-BBBB";
	private static final String POLLY_ONE = "8FzKq2mNbX";
	private static final String POLLY_TWO = "3RtWs9kLpQ";

	@Test
	@DisplayName("An owner key is stable for a credential, so myPollys keeps finding its pollys")
	public void ownerKeyIsStable() {
		assertEquals(PollyKeys.ownerKey(SECRET, CREDENTIAL), PollyKeys.ownerKey(SECRET, CREDENTIAL));
	}

	@Test
	@DisplayName("Different credentials get different owner keys")
	public void ownerKeyIsPerCredential() {
		assertNotEquals(PollyKeys.ownerKey(SECRET, CREDENTIAL), PollyKeys.ownerKey(SECRET, OTHER_CREDENTIAL));
	}

	@Test
	@DisplayName("A voter key is stable within one polly, which is what one-vote-per-passkey needs")
	public void voterKeyIsStableWithinAPolly() {
		assertEquals(PollyKeys.voterKey(SECRET, CREDENTIAL, POLLY_ONE),
				PollyKeys.voterKey(SECRET, CREDENTIAL, POLLY_ONE));
	}

	@Test
	@DisplayName("The same person is unlinkable across pollys")
	public void voterKeyDiffersPerPolly() {
		// This is the whole point of keying the voter hash on the polly as well as the credential:
		// two ballots in two different pollys must not be attributable to one person.
		assertNotEquals(PollyKeys.voterKey(SECRET, CREDENTIAL, POLLY_ONE),
				PollyKeys.voterKey(SECRET, CREDENTIAL, POLLY_TWO));
	}

	@Test
	@DisplayName("A voter key is never the owner key, so a ballot cannot be matched to a creator")
	public void voterKeyIsNotOwnerKey() {
		assertNotEquals(PollyKeys.ownerKey(SECRET, CREDENTIAL),
				PollyKeys.voterKey(SECRET, CREDENTIAL, POLLY_ONE));
	}

	@Test
	@DisplayName("Rotating the secret changes every derived key")
	public void keysDependOnTheSecret() {
		assertNotEquals(PollyKeys.ownerKey(SECRET, CREDENTIAL), PollyKeys.ownerKey("aDifferentSecret", CREDENTIAL));
	}

	@Test
	@DisplayName("The separator keeps concatenations from colliding")
	public void separatorPreventsAmbiguity() {
		// Without a separator, ("ab", "cd") and ("abc", "d") would hash the same input.
		assertNotEquals(PollyKeys.voterKey(SECRET, "ab", "cd"), PollyKeys.voterKey(SECRET, "abc", "d"));
	}

	@Test
	@DisplayName("Keys are hex of a SHA-256 HMAC, so they fit the 64 char columns")
	public void keysAreHexSha256() {
		String ownerKey = PollyKeys.ownerKey(SECRET, CREDENTIAL);
		assertEquals(64, ownerKey.length(), "owner_key column is length 64");
		assertTrue(ownerKey.matches("[0-9a-f]{64}"), "expected lowercase hex, got: " + ownerKey);
		assertEquals(64, PollyKeys.voterKey(SECRET, CREDENTIAL, POLLY_ONE).length(), "voter_key column is length 64");
	}

	@Test
	@DisplayName("A raw credential id never appears in a derived key")
	public void keysDoNotLeakTheCredentialId() {
		assertFalse(PollyKeys.ownerKey(SECRET, CREDENTIAL).contains(CREDENTIAL));
		assertFalse(PollyKeys.voterKey(SECRET, CREDENTIAL, POLLY_ONE).contains(CREDENTIAL));
	}
}
