package org.liquido.util;

import org.eclipse.microprofile.config.inject.ConfigProperties;

@ConfigProperties(prefix = "liquido")
public class LiquidoConfig {
    public String frontendUrl;
    public int loginLinkExpirationHours;

    public class Jwt {
        public String secret;
        public Long expirationSecs;
    }

}