package org.liquido.security.webauthn;

import io.quarkus.security.webauthn.WebAuthnUserProvider;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.auth.webauthn.AttestationCertificates;
import io.vertx.ext.auth.webauthn.Authenticator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoException;

import java.util.ArrayList;
import java.util.List;

/**
 * Expose our webauthn entities to Quarkus WebAuthn Library
 */
@Slf4j
@ApplicationScoped
@Blocking  // Defer calls to worker pool and not the IO thread
public class WebAuthnSetup implements WebAuthnUserProvider {

	@Transactional
	@Override
	public Uni<List<Authenticator>> findWebAuthnCredentialsByUserName(String userName) {
		log.info("findWebAuthnCredentialsByUserName: "+userName);
		return Uni.createFrom().item(toAuthenticators(WebAuthnCredential.findByUserName(userName)));
	}

	@Transactional
	@Override
	public Uni<List<Authenticator>> findWebAuthnCredentialsByCredID(String credID) {
		log.info("findWebAuthnCredentialsByCredID: "+credID);
		return Uni.createFrom().item(toAuthenticators(WebAuthnCredential.findByCredID(credID)));

	}

	@SneakyThrows  // Lombok :-)
	@Transactional
	@Override
	public Uni<Void> updateOrStoreWebAuthnCredentials(Authenticator authenticator) {
		log.info("updateOrStoreWebAuthnCredentials" + authenticator);

		// We assume that a user must already exist before WebAuthnCredentials can be added and stored
		//TODO: create own LiquidoException Error code for this exception
		UserEntity user = UserEntity.findByEmail(authenticator.getUserName())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot updateOrStoreWebAuthnCredentials. No user with username: " + authenticator.getUserName()));


		// IF this user has no credential yet, THEN Create one
		if (user.getWebAuthnCredential() == null) {
			log.info("Create new webAuthnCredentials for "+user.toStringShort());
			WebAuthnCredential credential = new WebAuthnCredential(authenticator, user);
			credential.persist();
			user.webAuthnCredential.counter = authenticator.getCounter();
			user.persist();
		} else {
			// ELSE update the counter of the webAuthnCredential
			log.info("Updating webAuthnCredentials for "+user.toStringShort()+ "from counter=" + user.webAuthnCredential.counter + " to counter="+authenticator.getCounter());
			user.webAuthnCredential.counter = authenticator.getCounter();
		}

		log.info("========== updateOrStoreWebAuthnCredentials: " + user.toString());

		return Uni.createFrom().nullItem();

		/*  original code from example that we adapted
		return UserEntity.findByEmail(authenticator.getUserName())
				.flatMap(user -> {
					// new user
					if(user == null) {
						User newUser = new User();
						newUser.userName = authenticator.getUserName();
						WebAuthnCredential credential = new WebAuthnCredential(authenticator, newUser);
						return credential.persist()
								.flatMap(c -> newUser.persist())
								.onItem().ignore().andContinueWithNull();
					} else {
						// existing user
						user.webAuthnCredential.counter = authenticator.getCounter();
						return Uni.createFrom().nullItem();
					}
				});

		 */
	}

	/** map a list of our WebauthnCredentials to a list of Vertx authenticators */
	private static List<Authenticator> toAuthenticators(List<WebAuthnCredential> dbs) {
		return dbs.stream().map(WebAuthnSetup::toAuthenticator).toList();
		/*
		// can't call combine/uni on empty list
		if(dbs.isEmpty())
			return Uni.createFrom().item(Collections.emptyList());
		List<Uni<Authenticator>> ret = new ArrayList<>(dbs.size());
		for (WebAuthnCredential db : dbs) {
			ret.add(toAuthenticator(db));
		}
		return Uni.combine().all().unis(ret).combinedWith(f -> (List)f);
		*/

	}

	private static Authenticator toAuthenticator(WebAuthnCredential credential) {
		Authenticator ret = new Authenticator();
		ret.setAaguid(credential.aaguid);
		AttestationCertificates attestationCertificates = new AttestationCertificates();
		attestationCertificates.setAlg(credential.alg);
		List<String> x5cs = new ArrayList<>(credential.x5cList.size());
		for (WebAuthnCertificate webAuthnCertificate : credential.x5cList) {
			x5cs.add(webAuthnCertificate.x5c);
		}
		attestationCertificates.setX5c(x5cs);
		ret.setAttestationCertificates(attestationCertificates);
		ret.setCounter(credential.counter);
		ret.setCredID(credential.credID);
		ret.setFmt(credential.fmt);
		ret.setPublicKey(credential.publicKey);
		ret.setType(credential.type);
		ret.setUserName(credential.userName);
		return ret;
		/*
		return credential.fetch(credential.x5c)
				.map(x5c -> {
						Authenticator ret = new Authenticator();
						ret.setAaguid(credential.aaguid);
						AttestationCertificates attestationCertificates = new AttestationCertificates();
						attestationCertificates.setAlg(credential.alg);
						List<String> x5cs = new ArrayList<>(x5c.size());
						for (WebAuthnCertificate webAuthnCertificate : x5c) {
								x5cs.add(webAuthnCertificate.x5c);
						}
						ret.setAttestationCertificates(attestationCertificates);
						ret.setCounter(credential.counter);
						ret.setCredID(credential.credID);
						ret.setFmt(credential.fmt);
						ret.setPublicKey(credential.publicKey);
						ret.setType(credential.type);
						ret.setUserName(credential.userName);
						return ret;
				});

		 */
	}

	/*

	//TODO: clean this up. Check the currently logged in user in his current team.
	@Override
	public Set<String> getRoles(String userEmail) {
		Set<String> roles = new HashSet<>();
		Optional<UserEntity> userOpt = UserEntity.findByEmail(userEmail);
		if (userOpt.isPresent()) {
			long teamId = userOpt.get().getLastTeamId();
			Optional<TeamEntity> teamOpt = TeamEntity.findByIdOptional(teamId);
			if (teamOpt.isPresent()) {
				if (teamOpt.get().isAdmin(userOpt.get())) {
					roles.add(TeamMemberEntity.Role.ADMIN.toString());
				}
			}
		}
		return roles;
	}

	 */
}