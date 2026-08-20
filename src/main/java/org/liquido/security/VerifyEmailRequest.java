package org.liquido.security;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.Length;

/**
 * Request body for {@link LoginRestAPI#verifyEmail}.
 *
 * In the body rather than the query string, following the same reasoning as
 * {@link ResetPasswordRequest}: a token in a query string ends up in server access logs, proxy logs
 * and browser history. It is admittedly weaker here, because the token also travels in the mail link
 * the frontend page was opened with - but there is no reason to copy it into the backend's logs too.
 *
 * Named "verifyToken", NOT "emailToken": that name belongs to the magic-link LOGIN flow
 * (LoginWithEmailTokenRequest), which is a different use case and produces a session. These two must
 * never be confused for one another.
 */
public record VerifyEmailRequest(
		@NotNull @Length(max = 100) String verifyToken
) {}
