package org.liquido.polly;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.liquido.util.LiquidoConfig;

import java.security.SecureRandom;

/**
 * Derives Polly's two keys from a WebAuthn credential id, and mints opaque public ids.
 *
 * <pre>
 *   ownerKey  = HMAC(secret, credentialId)                    stable per credential
 *   voterKey  = HMAC(secret, credentialId | polly.publicId)   per credential AND polly
 * </pre>
 *
 * <p>The owner key is stable so that {@code myPollys} can find everything a passkey created -
 * that is what replaces "email me my link". The voter key is per polly so that the same
 * person is <b>unlinkable across</b> different pollys: two ballots in two pollys cannot be
 * shown to belong to one voter without the server secret.
 *
 * <p>The raw credential id is never stored on a ballot, so a stolen database on its own
 * cannot link voters to ballots. The server still can, because it holds the secret - that is
 * the accepted trade for a poll among friends, and the UI says so.
 *
 * <h3>Why its own secret</h3>
 * Deliberately <b>not</b> {@code liquido.hash-secret}. That value is pinned forever: it is
 * baked into every {@code RightToVoteEntity} id, which is the foreign key on every ballot, so
 * changing it would orphan every vote ever cast. A separate {@code liquido.polly.hmac-secret}
 * can be rotated on its own - at the cost of resetting polly ownership, which is contained.
 */
@ApplicationScoped
public class PollyKeys {

	/**
	 * Base58: the digits and letters that cannot be confused for one another when read aloud
	 * or retyped. No 0/O, no I/l. Must stay in sync with the frontend's alphabet.
	 */
	private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

	/** 10 base58 chars is ~58 bits - far past guessing, and still short enough to read out. */
	private static final int PUBLIC_ID_LENGTH = 10;

	/**
	 * Separates the two halves of the voter key input so that no two different
	 * (credentialId, publicId) pairs can concatenate to the same string.
	 * Neither a base64url credential id nor a base58 public id can contain it.
	 */
	private static final String SEPARATOR = "|";

	private static final SecureRandom RANDOM = new SecureRandom();

	@Inject
	LiquidoConfig config;

	/** Stable for this credential across every polly. Backs ownership and {@code myPollys}. */
	public String ownerKey(@NonNull String credentialId) {
		return ownerKey(config.polly().hmacSecret(), credentialId);
	}

	/** Per polly, so the same person cannot be tracked from one polly to the next. */
	public String voterKey(@NonNull String credentialId, @NonNull String pollyPublicId) {
		return voterKey(config.polly().hmacSecret(), credentialId, pollyPublicId);
	}

	// The derivation itself is pure arithmetic and needs no CDI, so it is static and directly
	// testable. The instance methods above just supply the configured secret.

	public static String ownerKey(@NonNull String secret, @NonNull String credentialId) {
		return hmac(secret, credentialId);
	}

	public static String voterKey(@NonNull String secret, @NonNull String credentialId, @NonNull String pollyPublicId) {
		return hmac(secret, credentialId + SEPARATOR + pollyPublicId);
	}

	private static String hmac(String secret, String data) {
		return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secret).hmacHex(data);
	}

	/**
	 * A fresh opaque public id for the share link.
	 * Callers must check it is unused - see {@code PollyService.newUniquePublicId()}.
	 */
	public String newPublicId() {
		StringBuilder sb = new StringBuilder(PUBLIC_ID_LENGTH);
		for (int i = 0; i < PUBLIC_ID_LENGTH; i++) {
			sb.append(BASE58.charAt(RANDOM.nextInt(BASE58.length())));
		}
		return sb.toString();
	}

	/** A fresh opaque WebAuthn user handle. Must be unique per identity - see {@link PollyCredentialEntity}. */
	public String newUserHandle() {
		return "polly-" + java.util.UUID.randomUUID();
	}
}
