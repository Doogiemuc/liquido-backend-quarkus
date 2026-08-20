package org.liquido.util;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Turns a {@link LiquidoException} thrown from a REST endpoint into the HTTP status the error
 * already declares, with a JSON body the frontend can branch on.
 *
 * <h2>Why this was needed</h2>
 *
 * {@link LiquidoErrorExtensionProvider} only covers GraphQL - it puts the code into
 * {@code errors[0].extensions.liquidoException}. JAX-RS never sees that, so until this mapper
 * existed <b>every</b> REST endpoint that threw a LiquidoException answered with a bare HTTP 500
 * and Quarkus' default error page, including the exception message and source snippet. That is
 * wrong three times over: the status said "server broke" for what is usually a client mistake, the
 * frontend had no code to branch on, and internal details leaked to whoever called it.
 *
 * Affects {@code LoginRestAPI.resetPassword}, {@code loginWithEmailToken},
 * {@code requestEmailLoginLink}, {@code welcomeMail} and {@code verifyEmail}.
 *
 * <h2>Body shape</h2>
 *
 * Deliberately the same {@code liquidoException} envelope the GraphQL side produces, so a client
 * reads {@code liquidoException.liquidoErrorCode} either way and does not need two code paths.
 * The human-readable message is included for logs and debugging; clients should branch on the code.
 *
 * Modelled on {@link org.liquido.polly.PollyExceptionMapper}, which does the same for Polly.
 */
@Slf4j
@Provider
public class LiquidoExceptionMapper implements ExceptionMapper<LiquidoException> {

	@Override
	public Response toResponse(LiquidoException exception) {
		Response.Status status = exception.getHttpResponseStatus();
		log.debug("LIQUIDO REST error {} -> HTTP {}", exception.getErrorName(), status.getStatusCode());
		return Response.status(status)
				.type(MediaType.APPLICATION_JSON)
				.entity(Map.of(
						"liquidoException", Map.of(
								"liquidoErrorName", exception.getErrorName(),
								"liquidoErrorCode", exception.getErrorCodeAsInt(),
								"msg", exception.getMessage() != null ? exception.getMessage() : ""),
						"message", exception.getMessage() != null ? exception.getMessage() : ""))
				.build();
	}
}
