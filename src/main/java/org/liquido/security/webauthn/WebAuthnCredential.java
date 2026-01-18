package org.liquido.security.webauthn;

import com.fasterxml.jackson.annotation.JsonBackReference;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import jakarta.persistence.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.liquido.user.UserEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A persisted credential for two-factor authentication (2FA) with webauthn / passkey.
 * One user may have more than one credential registered.
 */
@Slf4j
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"credentialId"}))
@Entity
public class WebAuthnCredential extends PanacheEntityBase {

	// Very detailed description of data objects related to webauthn
	// https://developers.yubico.com/WebAuthn/WebAuthn_Developer_Guide/WebAuthn_Client_Registration.html

	// The official W3C spec (don't even try to read it!) Good docs are at https://webauthn.io/
	// https://www.w3.org/TR/webauthn/#registering-a-new-credential

	/**
	 * Unique ID of this authenticator.
	 * (This ID does not necessarily identify exactly one hardware device!)
	 */
	@Id
	public String credentialId;

	/**
	 * Human-readable name of this authenticator.
	 * To distinguish it from other authenticators a user might register.
	 * Can be set by the user.
	 */
	public String label;

	/** When was this authenticator last used to successfully(!) login */
	public LocalDateTime lastUsed;

	/**
	 * The liquidoUser that is linked to this authenticator.
	 * One user may have more than one authenticator device registered.
	 */
	@ManyToOne
	@JoinColumn(name = "liquido_user_id", nullable = false)
	@JsonBackReference
	public UserEntity liquidoUser;

	/**
	 * Authenticator Attestation GUID
	 * 128-bit identifier (UUID) that identifies the authenticator model,
	 * not a specific device and not a specific credential.
	 * From the WebAuthn / FIDO2 spec: "The AAGUID identifies the type (make and model) of the authenticator."
	 */
	public UUID aaguid;

	public byte[] publicKey;
	public long publicKeyAlgorithm;
	public long counter;

	public WebAuthnCredential() {
	}

	public WebAuthnCredential(WebAuthnCredentialRecord credentialRecord, @NonNull UserEntity user, String label) {
		WebAuthnCredentialRecord.RequiredPersistedData requiredPersistedData =
				credentialRecord.getRequiredPersistedData();
		aaguid = requiredPersistedData.aaguid();
		counter = requiredPersistedData.counter();
		credentialId = requiredPersistedData.credentialId();
		publicKey = requiredPersistedData.publicKey();
		publicKeyAlgorithm = requiredPersistedData.publicKeyAlgorithm();
		this.label = label;
		this.lastUsed = LocalDateTime.now();
		this.liquidoUser = user;
		user.webAuthnCredentials.add(this);
	}

	public WebAuthnCredentialRecord toWebAuthnCredentialRecord() {
		return WebAuthnCredentialRecord
				.fromRequiredPersistedData(
						new WebAuthnCredentialRecord.RequiredPersistedData(liquidoUser.email, credentialId,
								aaguid, publicKey,
								publicKeyAlgorithm, counter));
	}

	public static List<WebAuthnCredential> findByEmail(String email) {
		return list("liquidoUser.email", email);
	}

	public static WebAuthnCredential findByCredentialId(String credentialId) {
		return findById(credentialId);
	}
}