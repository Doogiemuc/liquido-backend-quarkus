package org.liquido.security.webauthn;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.vertx.ext.auth.webauthn.Authenticator;
import io.vertx.ext.auth.webauthn.PublicKeyCredential;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.liquido.user.UserEntity;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"userName", "credID"}))
@Entity
public class WebAuthnCredential extends PanacheEntity {

	/**
	 * The username linked to this authenticator
	 */
	public String userName;

	/**
	 * The type of key (must be "public-key")
	 */
	public String type = "public-key";

	/**
	 * The non user identifiable id for the authenticator.
	 *
	 * A single user can have more than one authenticator device,
	 * which means a single username can map to multiple credential IDs,
	 * all of which identify the same user.
	 *
	 * An authenticator device may be shared by multiple users,
	 * because a single person may want multiple user accounts with different usernames,
	 * all of which having the same authenticator device.
	 * So a single credential ID may be used by multiple different users.
	 */
	public String credID;

	/**
	 * The public key associated with this authenticator
	 */
	public String publicKey;

	/**
	 * The signature counter of the authenticator to prevent replay attacks
	 */
	public long counter;

	public String aaguid;

	/*
	 * The Authenticator attestation certificates object, a JSON like:
	 * <pre>{@code
	 *   {
	 *     "alg": "string",
	 *     "x5c": [
	 *       "base64"
	 *     ]
	 *   }
	 * }</pre>
	 */



	/**
	 * The algorithm used for the public credential
	 */
	@Enumerated(EnumType.STRING)    //FIX: https://vladmihalcea.com/the-best-way-to-map-an-enum-type-with-jpa-and-hibernate/
	//@Column(name="PK_Algo", length = 12)
	public PublicKeyCredential alg;

	/**
	 * The list of X509 certificates encoded as base64url.
	 */
	@OneToMany(mappedBy = "webAuthnCredential")
	public List<WebAuthnCertificate> x5cList = new ArrayList<>();

	public String fmt;

	// owning side
	@OneToOne
	public UserEntity user;

	public WebAuthnCredential() {
	}

	public WebAuthnCredential(Authenticator authenticator, UserEntity user) {
		aaguid = authenticator.getAaguid();
		if(authenticator.getAttestationCertificates() != null)
			alg = authenticator.getAttestationCertificates().getAlg();
		counter = authenticator.getCounter();
		credID = authenticator.getCredID();
		fmt = authenticator.getFmt();
		publicKey = authenticator.getPublicKey();
		type = authenticator.getType();
		userName = authenticator.getUserName();
		if(authenticator.getAttestationCertificates() != null
				&& authenticator.getAttestationCertificates().getX5c() != null) {
			for (String x5c : authenticator.getAttestationCertificates().getX5c()) {
				WebAuthnCertificate cert = new WebAuthnCertificate();
				cert.x5c = x5c;
				cert.webAuthnCredential = this;
				this.x5cList.add(cert);
			}
		}
		this.user = user;
		user.webAuthnCredential = this;
	}

	//TODO: Should we use anonymous usernames or unique emails (as usernames)?
	//      https://passwordless.id/thoughts/emails-vs-usernames
	public static List<WebAuthnCredential> findByUserName(String userName) {
		List<WebAuthnCredential> creds = WebAuthnCredential.list("userName", userName);
		for (WebAuthnCredential cred : creds) {
			log.info("   " + cred.userName+ ", " + cred.credID);
		}
		log.info("findByUserName(userName="+userName+") => found " + creds.size() + " WebAuthnCredential(s)");
		return creds;
	}

	public static List<WebAuthnCredential> findByCredID(String credID) {
		return WebAuthnCredential.list("credID", credID);
	}

	/** ???? TODO: do I need this?
	public <T> Uni<T> fetch(T association) {
		return getSession().flatMap(session -> session.fetch(association));
	}
	 */
}