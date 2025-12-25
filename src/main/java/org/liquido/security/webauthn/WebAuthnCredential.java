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
 * A persisted credential for two-factor authentication (2FA).
 * One user may have more than one credential registered.
 */
@Slf4j
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"credentialId"}))
@Entity
public class WebAuthnCredential extends PanacheEntityBase {

	@Id
	public String credentialId;

	/**
	 * The liquidoUser that is linked to this authenticator
	 */
	@ManyToOne
	@JoinColumn(name = "liquido_user_id", nullable = false)
	@JsonBackReference
	public UserEntity liquidoUser;

	public byte[] publicKey;
	public long publicKeyAlgorithm;
	public long counter;
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