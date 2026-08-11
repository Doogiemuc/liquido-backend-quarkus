package org.liquido.polly;

import jakarta.json.Json;
import jakarta.json.JsonValue;
import jakarta.ws.rs.ext.Provider;

/**
 * Puts {@code pollyErrorCode} into the GraphQL error extensions.
 *
 * <p>The frontend reads exactly {@code errors[0].extensions.pollyErrorCode} and branches on
 * the string. Two things are required for that to arrive at all:
 * <ol>
 *   <li>this class must be listed in
 *       {@code META-INF/services/io.smallrye.graphql.api.ErrorExtensionProvider}, and</li>
 *   <li>{@code pollyErrorCode} must appear in
 *       {@code quarkus.smallrye-graphql.error-extension-fields} - SmallRye drops any key that
 *       is not on that whitelist (see {@code ExecutionErrorsService.addKeyValue}).</li>
 * </ol>
 *
 * <p>Returning {@code null} for a non-polly throwable renders {@code "pollyErrorCode": null}
 * rather than omitting the key - that is SmallRye's behaviour, not a choice here. It is
 * harmless: the team frontend reads {@code extensions.liquidoException} and never looks at
 * this one.
 *
 * @see <a href="https://smallrye.io/smallrye-graphql/2.13.0/custom-error-extensions/">SmallRye custom error extensions</a>
 */
@Provider
public class PollyErrorExtensionProvider implements io.smallrye.graphql.api.ErrorExtensionProvider {

	@Override
	public String getKey() {
		return "pollyErrorCode";
	}

	@Override
	public JsonValue mapValueFrom(Throwable throwable) {
		if (throwable instanceof PollyException pe) {
			return Json.createValue(pe.getPollyError().name());
		}
		// Not a polly problem - let LiquidoErrorExtensionProvider describe it.
		return null;
	}
}
