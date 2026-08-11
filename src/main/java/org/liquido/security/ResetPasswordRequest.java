package org.liquido.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.Length;

/** Request body for {@link LoginRestAPI#resetPassword}. In the body, not the query string, because it carries a one-time token and a new password. */
public record ResetPasswordRequest(
		@NotNull @Email @Length(max = 100) String email,
		@NotNull String resetPasswordToken,
		@NotNull String newPassword
) {}
