package org.liquido.security.webauthn;

import com.fasterxml.jackson.annotation.JsonBackReference;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import jakarta.persistence.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.liquido.user.UserEntity;

import java.util.List;
import java.util.UUID;

/**
 * A persisted credential for two-factor authentication (2FA) with webauthn / keepass.
 * One user may have more than one credential registered.
 */
@Slf4j
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"credentialId"}))
@Entity
public class WebAuthnCredential extends PanacheEntityBase {

	@Id
	public String credentialId;

	//TODO: public String label;  // set by user
	//TODO: Datetime lastUsed;    // when this credential was last successfully used to log in

	/**
	 * The liquidoUser that is linked to this authenticator.
	 * One user may have more than one authenticator device registered.
	 */
	@ManyToOne
	@JoinColumn(name = "liquido_user_id", nullable = false)
	@JsonBackReference
	public UserEntity liquidoUser;

	public byte[] publicKey;
	public long publicKeyAlgorithm;
	public long counter;

	/**
	 * Authenticator Attestation GUID
	 * 128-bit identifier (UUID) that identifies the authenticator model,
	 * not a specific device and not a specific credential.
	 * From the WebAuthn / FIDO2 spec: "The AAGUID identifies the type (make and model) of the authenticator."
	 */
	public UUID aaguid;

	public WebAuthnCredential() {
	}

	public WebAuthnCredential(WebAuthnCredentialRecord credentialRecord, @NonNull UserEntity user) {
		WebAuthnCredentialRecord.RequiredPersistedData requiredPersistedData =
				credentialRecord.getRequiredPersistedData();
		aaguid = requiredPersistedData.aaguid();
		counter = requiredPersistedData.counter();
		credentialId = requiredPersistedData.credentialId();
		publicKey = requiredPersistedData.publicKey();
		publicKeyAlgorithm = requiredPersistedData.publicKeyAlgorithm();
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