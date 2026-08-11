package org.liquido.polly;

/**
 * Error codes of the Polly module.
 *
 * <p>Deliberately <b>strings in their own namespace</b>, not the numeric
 * {@link org.liquido.util.LiquidoException.Errors} codes. The frontend reads them from
 * {@code errors[0].extensions.pollyErrorCode} and keeps its own constants in
 * {@code src/polly/polly-constants.js}; {@code src/services/LiquidoExceptionCodes.js} and the
 * generator that produces it stay untouched.
 *
 * <p>These names are a contract with the frontend. Do not rename one without changing
 * {@code polly-constants.js} in the same breath.
 */
public enum PollyError {

	/** No polly with that public id. Also returned for a malformed id - never leak which. */
	POLLY_NOT_FOUND,

	/** This passkey already cast a ballot here. Backed by {@code UNIQUE (polly_id, voter_key)}. */
	ALREADY_VOTED,

	/** Edit and Finish are the owner's alone. */
	NOT_POLLY_OWNER,

	/** Cannot change a polly once somebody has voted. */
	POLLY_ALREADY_STARTED,

	/** The polly is closed; no more votes and no more edits. */
	POLLY_FINISHED,

	/** This operation needs a passkey session and there is none. */
	NEED_PASSKEY,

	/** Missing title, fewer than two options, or a vote order that does not fit the polly. */
	INVALID_POLLY
}
