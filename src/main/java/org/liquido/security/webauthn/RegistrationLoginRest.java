package org.liquido.security.webauthn;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.authenticator.Authenticator;
import com.webauthn4j.authenticator.AuthenticatorImpl;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.data.*;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.validator.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.util.Collections;
import java.util.List;

@Slf4j
@Path("/liquido-api/v5")
public class RegistrationLoginRest {

	// Client properties
	//byte[] attestationObject = null /* set attestationObject */;
	//byte[] clientDataJSON = null /* set clientDataJSON */;
	//String clientExtensionJSON = null;  /* set clientExtensionJSON */
	//Set<String> transports = null /* set transports */;

	// Server properties
	Origin origin = Origin.create("liquido.vote") /* set origin */;
	String rpId = "liquido.vote" /* set rpId */;
	Challenge challenge = new DefaultChallenge() /* set challenge, randomly genereated */;
	byte[] tokenBindingId = null /* set tokenBindingId  can be null*/;
	ServerProperty serverProperty = new ServerProperty(origin, rpId, challenge, tokenBindingId);

	// expectations
	boolean userVerificationRequired = false;
	boolean userPresenceRequired = true;


	@POST
	@Path("/register")
	public Authenticator register(RegistrationRequest registrationRequest) {

		WebAuthnManager webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();

		PublicKeyCredentialParameters credentialParameters = new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256);
		List<PublicKeyCredentialParameters> credsList = Collections.singletonList(credentialParameters);
		RegistrationParameters registrationParameters = new RegistrationParameters(serverProperty, credsList, userVerificationRequired, userPresenceRequired);
		RegistrationData registrationData;
		try {
			registrationData = webAuthnManager.parse(registrationRequest);
		} catch (DataConversionException e) {
			// If you would like to handle WebAuthn data structure parse error, please catch DataConversionException
			log.error("Cannot parse registration Request "+e);
			throw e;
		}
		try {
			webAuthnManager.validate(registrationData, registrationParameters);
		} catch (ValidationException e) {
			log.error("Cannot validate registration Request "+e);
			// If you would like to handle WebAuthn data validation error, please catch ValidationException
			throw e;
		}

		// please persist Authenticator object, which will be used in the authentication process.
		Authenticator authenticator =
				new AuthenticatorImpl( // You may create your own Authenticator implementation to save friendly authenticator name
						registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData(),
						registrationData.getAttestationObject().getAttestationStatement(),
						registrationData.getAttestationObject().getAuthenticatorData().getSignCount()
				);

		//TODO: save(authenticator); // please persist authenticator in your manner

		return authenticator;
	}


}
