package org.liquido.security.webauthn;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;


@Entity
public class WebAuthnCertificate extends PanacheEntity {

	@ManyToOne
	public WebAuthnCredential webAuthnCredential;

	/**
	 * A X509 certificate encoded as base64url.
	 */
	public String x5c;
}