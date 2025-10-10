package org.liquido.util;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.NotNull;

/**
 * LIQUIDO configurations from application.properties
 */
@ConfigMapping(prefix = "liquido")
public interface LiquidoConfig {
    String apiVersion();

    @NotNull
    String frontendUrl();

    int loginLinkExpirationHours();

    @NotNull
    int durationOfVotingPhase();

    @WithDefault("365")
    int rightToVoteExpirationDays();

    @WithDefault("20")
    int voterTokenExpirationMinutes();

    @NotNull
    String hashSecret();  // the secret only know to the server that is used to create rightToVote tokens

    String devLoginToken();

    String testPasswordResetToken();

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