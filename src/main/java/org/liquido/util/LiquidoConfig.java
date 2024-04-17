package org.liquido.util;

import io.smallrye.config.ConfigMapping;

/**
 * LIQUIDO configurations from application.properties
 */
//DEPRECATED @ConfigProperties(prefix = "liquido")
@ConfigMapping(prefix = "liquido")
public interface LiquidoConfig {
    String frontendUrl();
    int loginLinkExpirationHours();
    int durationOfVotingPhase();
    int rightToVoteExpirationHours();
    String hashSecret();  // the secret only know to the server that is used to create rightToVote tokens
    String devLoginToken();

    Jwt jwt();
    interface Jwt {
        //String secret();   not used
        Long expirationSecs();
    }

    Twilio twilio();
    interface Twilio {
        String accountSid();
        String authToken();
        String serviceSid();
    }

}