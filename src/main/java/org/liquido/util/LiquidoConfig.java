package org.liquido.util;

import com.twilio.Twilio;
import io.smallrye.config.ConfigMapping;

/**
 * LIQUIDO configurations from application.properties
 */
//TODO: Do I need this? @StaticInitSafe
//DEPRECATED @ConfigProperties(prefix = "liquido")
@ConfigMapping(prefix = "liquido")
public interface LiquidoConfig {
    String frontendUrl();
    int loginLinkExpirationHours();
    int durationOfVotingPhase();
    int rightToVoteExpirationHours();
    String hashSecret();

    Jwt jwt();
    interface Jwt {
        String secret();
        Long expirationSecs();
    }

    Twilio twilio();
    interface Twilio {
        String accountSid();
        String authToken();
        String serviceSid();
    }

}