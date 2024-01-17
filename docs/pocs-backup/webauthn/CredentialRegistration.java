package org.liquido.security.webauthn;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.liquido.user.UserEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.SortedSet;

@Value
@Builder
@With
public class CredentialRegistration {

	//adapted from: https://github.com/Yubico/java-webauthn-server/blob/main/webauthn-server-demo/src/main/java/demo/webauthn/data/CredentialRegistration.java#L42=

	UserEntity userIdentity;
	Optional<String> credentialNickname;
	SortedSet<AuthenticatorTransport> transports;

	@JsonIgnore
	Instant registrationTime;
	RegisteredCredential credential;

	Optional<Object> attestationMetadata;

	@JsonProperty("registrationTime")
	public String getRegistrationTimestamp() {
		return registrationTime.toString();
	}

	/** We use email as username! */
	public String getUsername() {
		return userIdentity.getEmail();
	}
}