package org.liquido.util;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.NotNull;

import java.util.Optional;

/**
 * LIQUIDO configurations from application.properties
 */
@ConfigMapping(prefix = "liquido")
public interface LiquidoConfig {
    String apiVersion();

		// URL of liquido frontend. Use for link in login email.
    @NotNull
    String frontendUrl();

		// login link in email is only valid for this long.
		@WithDefault("10")
    int loginLinkExpirationMinutes();

    @NotNull
    int durationOfVotingPhase();

    @WithDefault("365")
    int rightToVoteExpirationDays();

    @WithDefault("20")
    int voterTokenExpirationMinutes();

    @NotNull
    String hashSecret();  // the secret only know to the server that is used to create rightToVote tokens

		// (optional) login token that can be used to login during dev. (This CANNOT be used PROD!)
		@WithName("dev-login-token")
    Optional<String> devLoginTokenOpt();

		/* MAYBE: would also be possible. But I don't like the RuntimeException.
		default String devLoginToken() {
			return devLoginTokenOpt().orElseThrow(
					() -> new RuntimeException("DevLogin token is not defined in config")
			);
		}
		*/

		// used to do a real cypress E2E test of the password reset process (This CANNOT be used in PROD!)
		@WithName("testPasswordResetToken")
    Optional<String> testPasswordResetTokenOpt();

		@WithDefault("10")
    int minPasswordLength();

    Jwt jwt();
    interface Jwt {
        //String secret();   not used
        @WithDefault("60")
        Long expirationSecs();
    }

    Twilio twilio();
    interface Twilio {
        String accountSid();
        String authToken();
        String serviceSid();
    }

}