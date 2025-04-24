package org.liquido.user;


import io.smallrye.graphql.api.ErrorCode;

/**
 * This is a GraphQL exception.
 * It is for example used when a user tries to login via email
 * but provides an unknown e-mail.
 *
 * https://quarkus.io/guides/smallrye-graphql#error-code
 *
 * There is more config in {@link org.liquido.util.LiquidoErrorExtensionProvider}
 * that then adds the liqudoErrorCode into the extensions attribute of the GraphQL error response.
 */
@Deprecated
@ErrorCode("no-such-user")
public class NoSuchUserException extends Exception {
	//MAYBE: This would be a possible way to add specific errors to GraphQL responses
	// BUT this ErrorCode annotation is "subject to change". => We should not rely on it.
	// Instead I implemented a LiquidoErrorExtensionsProvider

	public NoSuchUserException(String message) {
		super(message);
	}
}