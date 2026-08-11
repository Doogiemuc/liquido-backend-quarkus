package org.liquido.util;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.UnauthorizedException;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.liquido.polly.PollyException;

/**
 * Add liquido specific error codes to the GraphQL response.
 * This class MUST be registered in
 * META-INF/services/io.smallrye.graphql.api.ErrorExtensionProvider (this is a ServiceLoader)
 *
 * <a href="https://smallrye.io/smallrye-graphql/2.13.0/custom-error-extensions/">...</a>
 */
@Slf4j
@Provider
public class LiquidoErrorExtensionProvider implements io.smallrye.graphql.api.ErrorExtensionProvider {
	@Override
	public String getKey() {
		return "liquidoException";
	}

	/**
	 * Add fields to the "extensions" attribute in the GraphQL response.
	 * Liquido always returns the same set of fields
	 * @param throwable The thrown exception
	 * @return JSON with info about what happened.
	 */
	@Override
	public JsonValue mapValueFrom(Throwable throwable) {
		// A polly error is described by PollyErrorExtensionProvider under its own key.
		// Without this branch it would fall through to the catch-all below, which log.error()s
		// it and attaches a bogus "This should not have happened" 500 to every ordinary
		// NEED_PASSKEY or ALREADY_VOTED.
		if (throwable instanceof PollyException) return null;

		JsonObjectBuilder builder = Json.createObjectBuilder();
		if (throwable instanceof LiquidoException le) {
			builder
					.add("liquidoErrorName", le.getErrorName())
					.add("liquidoErrorCode", le.getErrorCodeAsInt())
					.add("liquidoErrorMessage", le.getMessage());
		} else
		if (throwable instanceof UnauthorizedException) {
			builder
					.add("liquidoErrorName", LiquidoException.Errors.UNAUTHORIZED.name())
					.add("liquidoErrorCode", LiquidoException.Errors.UNAUTHORIZED.getLiquidoErrorCode())
					.add("liquidoErrorMessage", "Unauthorized GraphQL query. Must pass JWT in header!");
		} else {
			log.error("Exception {}: {}", throwable.getClass(), throwable.getMessage(), throwable);
			 builder
					.add("liquidoErrorName", "liquidoSystemError")
					.add("liquidoErrorCode", LiquidoException.Errors.INTERNAL_ERROR.getLiquidoErrorCode())
					.add("liquidoErrorMessage", "This should not have happened :-(   We are sorry.");
		}

		if (LaunchMode.current() == LaunchMode.DEVELOPMENT || LaunchMode.current() == LaunchMode.TEST) {
			String msg = throwable.getMessage() != null ? throwable.getMessage() :  "";  // msg must not be null
			builder
					.add("throwableException", throwable.getClass().getName())
					.add("throwableMessage", msg);
		}
		return builder.build();
	}
}