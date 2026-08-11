package org.liquido.polly;

import lombok.Getter;

/**
 * Anything that can go wrong in a polly, carrying the {@link PollyError} the frontend
 * branches on.
 *
 * <p>Unchecked, unlike {@link org.liquido.util.LiquidoException}. Polly's resolvers are a thin
 * layer over one service, so a checked exception would buy nothing but {@code throws} clauses.
 *
 * <p>Surfaces as {@code errors[0].extensions.pollyErrorCode} via
 * {@link PollyErrorExtensionProvider}.
 */
@Getter
public class PollyException extends RuntimeException {

	private final PollyError pollyError;

	public PollyException(PollyError pollyError, String message) {
		super(message);
		this.pollyError = pollyError;
	}

	public static PollyException notFound(String publicId) {
		// Deliberately says nothing about whether the id was malformed or simply unknown.
		return new PollyException(PollyError.POLLY_NOT_FOUND, "Polly " + publicId + " not found");
	}

	public static PollyException needPasskey() {
		return new PollyException(PollyError.NEED_PASSKEY, "No polly passkey session");
	}

	public static PollyException notOwner() {
		return new PollyException(PollyError.NOT_POLLY_OWNER, "Only the polly owner may do that");
	}

	public static PollyException invalid(String message) {
		return new PollyException(PollyError.INVALID_POLLY, message);
	}
}
