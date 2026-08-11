package org.liquido.polly;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.security.JwtTokenUtils;
import org.liquido.util.DoogiesUtil;

import java.util.Optional;

/**
 * Who is asking, in Polly terms: a WebAuthn credential id, or nobody at all.
 *
 * <p>The polly counterpart of {@link JwtTokenUtils#getCurrentUser()}, and deliberately
 * separate from it. A polly caller has no {@code UserEntity}, no team and no email, so
 * {@code getCurrentUser()} would do a pointless database lookup and log a
 * "Valid JWT, but user not found" warning on every single request. Polly code must never
 * call it.
 *
 * <p>"Nobody at all" is a first-class, expected state: a polly must be readable with no
 * session, because the friend who just opened the link has not decided to vote yet.
 */
@RequestScoped
public class PollySession {

	@Inject
	JsonWebToken jwt;

	@Inject
	PollyKeys pollyKeys;

	/**
	 * The credential id of the caller's passkey, if they have a polly session.
	 *
	 * <p>Checks the group, not just the presence of a token: a perfectly valid <i>team</i> JWT
	 * must not be mistaken for a polly session, or a logged-in team member would silently
	 * become the "owner" of pollys keyed on their email address.
	 */
	public Optional<String> credentialId() {
		if (jwt == null || DoogiesUtil.isEmpty(jwt.getSubject())) return Optional.empty();
		if (jwt.getGroups() == null || !jwt.getGroups().contains(JwtTokenUtils.LIQUIDO_POLLY_ROLE)) return Optional.empty();
		return Optional.of(jwt.getSubject());
	}

	/** @throws PollyException NEED_PASSKEY when there is no polly session */
	public String requireCredentialId() {
		return credentialId().orElseThrow(PollyException::needPasskey);
	}

	/** The caller's stable owner key, or empty when there is no session. */
	public Optional<String> ownerKey() {
		return credentialId().map(pollyKeys::ownerKey);
	}

	/** @throws PollyException NEED_PASSKEY when there is no polly session */
	public String requireOwnerKey() {
		return pollyKeys.ownerKey(requireCredentialId());
	}

	/** The caller's voter key for one specific polly, or empty when there is no session. */
	public Optional<String> voterKey(String pollyPublicId) {
		return credentialId().map(credentialId -> pollyKeys.voterKey(credentialId, pollyPublicId));
	}

	public boolean hasSession() {
		return credentialId().isPresent();
	}
}
