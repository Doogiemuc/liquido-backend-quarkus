package org.liquido.util;

import io.quarkus.runtime.LaunchMode;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

/**
 * Add liquido specific error codes to the GraphQL response.
 * This class MUST be registered in
 * META-INF/services/io.smallrye.graphql.api.ErrorExtensionProvider (this is a ServiceLoader)
 *
 * https://smallrye.io/smallrye-graphql/2.13.0/custom-error-extensions/
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
	 * @return JSON with info about what happend.
	 */
	@Override
	public JsonValue mapValueFrom(Throwable throwable) {
		if (throwable instanceof LiquidoException le) {
			return Json.createObjectBuilder()
					.add("liquidoErrorName", le.getErrorName())
					.add("liquidoErrorCode", le.getErrorCodeAsInt())
					.add("liquidoErrorMessage", le.getMessage())
					.build();
		}
		JsonObjectBuilder builder = Json.createObjectBuilder()
				.add("liquidoErrorName", "liquidoSystemError")
				.add("liquidoErrorCode", LiquidoException.Errors.INTERNAL_ERROR.getLiquidoErrorCode())
				.add("liquidoErrorMessage", "This should not have happend :-(   We are sorry.");

		if (LaunchMode.current() == LaunchMode.DEVELOPMENT) {
			builder
					.add("throwableException", throwable.getClass().getName())
					.add("throwableMessage", throwable.getMessage());
		}
		return builder.build();
	}
}