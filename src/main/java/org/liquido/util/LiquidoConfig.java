package org.liquido.util;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;
import org.eclipse.microprofile.config.inject.ConfigProperties;

@StaticInitSafe
//DEPRECATED @ConfigProperties(prefix = "liquido")
@ConfigMapping(prefix = "liquido")
public interface LiquidoConfig {
    public String frontendUrl();
    public int loginLinkExpirationHours();

    public Jwt jwt();
    public interface Jwt {
        public String secret();
        public Long expirationSecs();
    }

}