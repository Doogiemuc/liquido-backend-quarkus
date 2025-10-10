package org.liquido.util;

import jakarta.json.Json;
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

	@Override
	public JsonValue mapValueFrom(Throwable throwable) {
		if (throwable instanceof LiquidoException) {
			LiquidoException le = (LiquidoException) throwable;
			return Json.createObjectBuilder()
					.add("liquidoErrorName", le.getErrorName())
					.add("liquidoErrorCode", le.getErrorCodeAsInt())
					.add("liquidoErrorMessage", le.getMessage())
					.build();
		}
		return Json.createValue("unknownException=" + throwable.getClass().getSimpleName());
	}
}