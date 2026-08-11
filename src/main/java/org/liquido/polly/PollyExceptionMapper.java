package org.liquido.polly;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Turns a {@link PollyException} thrown from {@link PollyWebAuthnRestApi} into a clean 4xx
 * with the same {@code pollyErrorCode} the GraphQL side uses, instead of a bare 500.
 *
 * <p>Only the REST endpoints need this. GraphQL never reaches JAX-RS exception mapping - there
 * the code arrives via {@link PollyErrorExtensionProvider} in the response's
 * {@code errors[0].extensions}.
 */
@Slf4j
@Provider
public class PollyExceptionMapper implements ExceptionMapper<PollyException> {

	@Override
	public Response toResponse(PollyException exception) {
		Response.Status status = statusFor(exception.getPollyError());
		log.debug("Polly REST error {} -> HTTP {}", exception.getPollyError(), status.getStatusCode());
		return Response.status(status)
				.type(MediaType.APPLICATION_JSON)
				.entity(Map.of(
						"pollyErrorCode", exception.getPollyError().name(),
						"message", exception.getMessage() != null ? exception.getMessage() : ""))
				.build();
	}

	private Response.Status statusFor(PollyError error) {
		return switch (error) {
			case POLLY_NOT_FOUND -> Response.Status.NOT_FOUND;
			case NEED_PASSKEY, NOT_POLLY_OWNER -> Response.Status.UNAUTHORIZED;
			case ALREADY_VOTED, POLLY_ALREADY_STARTED, POLLY_FINISHED -> Response.Status.CONFLICT;
			case INVALID_POLLY -> Response.Status.BAD_REQUEST;
		};
	}
}
