package org.liquido.util;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;

import java.util.Optional;

/**
 * LIQUIDO configurations from application.properties
 */
@ConfigMapping(prefix = "liquido")
public interface LiquidoConfig {
	/** version of liquido backend API. Used in response to ping request */
	String apiVersion();

	/** URL of liquido frontend. Used as prefix for link in login email. */
	@NotNull
	String frontendUrl();

	/** Login link in email is only valid for this long. */
	@WithDefault("10")
	int loginLinkExpirationMinutes();

	/** How long do polls run by default (TODO: future bigger LIQUIDO) */
	@NotNull
	int durationOfVotingPhase();

	/** When does a right to vote expire when a voter doesn't use it anymore? */
	@WithDefault("365")
	int rightToVoteExpirationDays();

	/** When does a voter token expire that a voter just fetched for a poll */
	@WithDefault("20")
	int voterTokenExpirationMinutes();

	/** Length of team invide codes. Must match frontend config!!! */
	@WithDefault("8")
	int inviteCodeLength();

	/** Keep passwords secure! */
	@WithDefault("10")
	int minPasswordLength();


	/** Used for login with google */
	@NonNull
	String googleClientId();

	/** the secret only know to the server that is used to create rightToVote tokens */
	@NotNull
	String hashSecret();

	/** (optional) login token that can be used to login during dev. (This CANNOT be used PROD!) */
	@WithName("dev-login-token")
	Optional<String> devLoginTokenOpt();

	/* MAYBE: would also be possible. But I don't like the RuntimeException.
	default String devLoginToken() {
		return devLoginTokenOpt().orElseThrow(
				() -> new RuntimeException("DevLogin token is not defined in config")
		);
	}
	*/

	/** (optional) token that is used in cypress E2E test to automatically test the full the password reset process (This CANNOT be used in PROD!) */
	@WithName("test-password-reset-token")
	Optional<String> testPasswordResetTokenOpt();

	/** Login JsonWebtoken */
	Jwt jwt();

	interface Jwt {
		@WithDefault("60")
		Long expirationSecs();
	}

	/** Sending SMS */
	Twilio twilio();
	interface Twilio {
		String accountSid();
		String authToken();
		String serviceSid();
	}

}