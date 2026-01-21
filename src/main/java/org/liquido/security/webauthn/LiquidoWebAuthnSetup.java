package org.liquido.security.webauthn;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnUserProvider;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.JwtTokenUtils;
import org.liquido.user.UserEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Unbelievably secure biometric authentification of human beings.
 *
 * Keep in mind, that even 2FA only verifies what someone knows (1st factor, your password) and possesses
 * (2nd factor, for example a YubiKey). But both could be stolen or duplicated.
 *
 * WebAuthn checks biometric data such as face recognition or fingerprint.
 *
 * https://quarkus.io/guides/security-webauthn
 * https://quarkus.io/guides/security-webauthn#exposing-your-entities-to-quarkus-webauthn
 * https://javadoc.io/doc/io.quarkus/quarkus-security-webauthn/latest/io/quarkus/security/webauthn/package-summary.html
 */
@Slf4j
@Blocking
@ApplicationScoped
public class LiquidoWebAuthnSetup implements WebAuthnUserProvider {

	@Inject
	SecurityIdentity securityIdentity;

	@Transactional
	@Override
	public Uni<List<WebAuthnCredentialRecord>> findByUsername(String email) {
		List<WebAuthnCredentialRecord> records = WebAuthnCredential.findByEmail(email).stream()
				.map(WebAuthnCredential::toWebAuthnCredentialRecord)
				.collect(Collectors.toList());
		return Uni.createFrom().item(records);
	}

	@Transactional
	@Override
	public Uni<WebAuthnCredentialRecord> findByCredentialId(String credId) {
		WebAuthnCredential creds = WebAuthnCredential.findByCredentialId(credId);
		if(creds == null)
			return Uni.createFrom()
					.failure(new RuntimeException("No such credential ID"));
		return Uni.createFrom().item(creds.toWebAuthnCredentialRecord());
	}

	/**
	 * Store a credential Record for a logged in liquido user
	 * https://quarkus.io/guides/security-webauthn#exposing-your-entities-to-quarkus-webauthn
	 * @param credentialRecord DTO
	 * @return always Uni&lt;void&gt;
	 */
	@Transactional
	@Override
	public Uni<Void> store(WebAuthnCredentialRecord credentialRecord) {
		//SECURITY: ONLY(!) allow creating new credentials for a logged-in user for themself.
		if (securityIdentity == null ||securityIdentity.isAnonymous()) {
			throw new IllegalStateException("User must be authenticated to register a new credential.");
		}
		String currentUsername = securityIdentity.getPrincipal().getName();
		String email = credentialRecord.getUsername();
		if (!currentUsername.equals(email)) {
			throw new IllegalStateException("A new authenticator MUST be registered for your user's email address: " + email);
		}
		UserEntity 	user = UserEntity.findByEmail(email)
				.orElseThrow(() -> new IllegalStateException("Cannot store WebAuthn credential. User with email '" + email + "' not found. A user must be created before a credential can be stored."));
		String label = user.getName()+"-Passkey";
		WebAuthnCredential credential = new WebAuthnCredential(credentialRecord, user, label);
		credential.persist();
		user.webAuthnCredentials.add(credential);  // also update the in-memory user object
		log.info("Stored new WebAuthn credential(id={}) for user {}", credential.credentialId, user.toStringShort());
		return Uni.createFrom().voidItem();
	}

	@Transactional
	@Override
	public Uni<Void> update(String credentialId, long counter) {
		WebAuthnCredential credential = WebAuthnCredential.findByCredentialId(credentialId);
		if (credential == null) {
			log.error("Could not find WebAuthn credential with id {} to update counter.", credentialId);
			return Uni.createFrom().failure(new IllegalStateException("Cannot find credential with id: " + credentialId));
		}
		credential.counter = counter;
		return Uni.createFrom().voidItem();
	}

	@Override
	public Set<String> getRoles(String userId) {
		// This method provides roles for a user during authentication.
		// It should be self-contained and rely only on the database, not on a JWT or request context.
		// The 'userId' parameter is the username, which is the user's email in our case.
		Optional<UserEntity> userOpt = UserEntity.findByEmail(userId);

		HashSet<String> roles = new HashSet<>();
		if (userOpt.isPresent()) {
			// Every authenticated user gets the base user role.
			roles.add(JwtTokenUtils.LIQUIDO_USER_ROLE);

			// The ADMIN role in LIQUIDO is context-dependent (per team).
			// The WebAuthn authentication process itself doesn't have a team context.
			// The team context is established *after* login, when a JWT is created with a teamId claim.
			// Therefore, we cannot grant a team-specific ADMIN role here.
			// The user's admin status will be correctly evaluated when the JWT is generated upon successful login.
			log.debug("Granted base role '{}' to user '{}'. Admin role is determined post-login.", JwtTokenUtils.LIQUIDO_USER_ROLE, userId);
		} else {
			log.warn("getRoles() was called for a non-existent user: {}", userId);
		}
		return roles;
	}
}