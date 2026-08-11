package org.liquido.polly;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A passkey registered through Polly, belonging to no LIQUIDO account at all.
 *
 * <p>This is why it cannot live in {@code WebAuthnCredential}: that entity has a
 * {@code liquido_user_id NOT NULL} pointing at a {@code UserEntity}, and a polly voter never
 * has one. No email, no name, no account - the passkey <i>is</i> the identity.
 *
 * <p>{@link #userHandle} is an opaque {@code polly-<uuid>} minted at registration time and
 * used as the WebAuthn {@code user.id}. It has to be unique per identity: WebAuthn keys a
 * credential on {@code (rpId, user.id)}, so a shared handle would make a second registration
 * on a device silently <b>replace</b> the first.
 *
 * <p>Only {@code credentialId} is ever used for lookups (see
 * {@code LiquidoWebAuthnSetup.findByCredentialId}) - the assertion tells us the raw id, and
 * that is all Polly needs to derive an owner key and a voter key.
 */
@Slf4j
@Entity
@Table(name = "polly_credential", uniqueConstraints = {
		@UniqueConstraint(name = "uq_polly_credential_handle", columnNames = {"user_handle"})
})
public class PollyCredentialEntity extends PanacheEntityBase {

	/** Unique id of this authenticator, as sent back in the assertion. */
	@Id
	@Column(name = "credential_id")
	public String credentialId;

	/** Opaque {@code polly-<uuid>}. Stands in for the username the WebAuthn API insists on. */
	@Column(name = "user_handle", nullable = false)
	public String userHandle;

	/** Authenticator model id (make and model, not a specific device). */
	public UUID aaguid;

	@Column(name = "public_key")
	public byte[] publicKey;

	@Column(name = "public_key_algorithm")
	public long publicKeyAlgorithm;

	/** WebAuthn signature counter, for replay protection. */
	public long counter;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	public LocalDateTime createdAt;

	@Column(name = "last_used")
	public LocalDateTime lastUsed;

	public PollyCredentialEntity() {
	}

	public PollyCredentialEntity(WebAuthnCredentialRecord credentialRecord, String userHandle) {
		WebAuthnCredentialRecord.RequiredPersistedData data = credentialRecord.getRequiredPersistedData();
		this.credentialId = data.credentialId();
		this.userHandle = userHandle;
		this.aaguid = data.aaguid();
		this.publicKey = data.publicKey();
		this.publicKeyAlgorithm = data.publicKeyAlgorithm();
		this.counter = data.counter();
		this.lastUsed = LocalDateTime.now();
	}

	public WebAuthnCredentialRecord toWebAuthnCredentialRecord() {
		return WebAuthnCredentialRecord.fromRequiredPersistedData(
				new WebAuthnCredentialRecord.RequiredPersistedData(
						userHandle, credentialId, aaguid, publicKey, publicKeyAlgorithm, counter));
	}

	public static PollyCredentialEntity findByCredentialId(String credentialId) {
		return findById(credentialId);
	}

	@Override
	public String toString() {
		return "PollyCredential[userHandle=" + userHandle + "]";   // never log the credentialId
	}
}
