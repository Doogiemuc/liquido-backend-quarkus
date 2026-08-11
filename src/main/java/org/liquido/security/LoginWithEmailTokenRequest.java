package org.liquido.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.Length;

/** Request body for {@link LoginRestAPI#loginWithEmailToken}. In the body, not the query string, because it carries a one-time login token. */
public record LoginWithEmailTokenRequest(
		@NotNull @Email @Length(max = 100) String email,
		@NotNull @Length(max = 100) String emailToken
) {}
