package org.liquido.user;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.OneTimeToken;
import org.liquido.services.TwilioVerifyClient;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;
import org.liquido.util.Lson;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.liquido.util.LiquidoException.Errors;

/**
 * GraphQL queries related to authentication of liquido users.
 */
@GraphQLApi
@Slf4j
public class UserGraphQL {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	Mailer mailer;

	/*

	@Inject
	io.smallrye.graphql.api.Context smallryeContext;

	// THIS DOES NOT WORK!!! with GraphQL
	@Context
	SecurityContext ctx;


	// QuarkusSecurityIdentity can directly be injected. Roles are already set from JWT claims "groups".
	// ((QuarkusHttpUser)request.getCurrent().user()).getSecurityIdentity()
	// See docu https://quarkus.io/guides/security-oidc-bearer-token-authentication-tutorial
	// This has been moved to my class JwtTokenUtils.java
	@Inject
	SecurityIdentity securityIdentity;
	 */

	/** Json Web Token that has been sent with request. */
	@Inject
	JsonWebToken jwt;

	@Inject
	CurrentVertxRequest request;

	@Inject
	TwilioVerifyClient twilioVerifyClient;

	@Inject
	LiquidoConfig config;

	/**
	 * Ping the API for availability
	 * @return some JSON info about API version
	 */
	@Query("ping")
	public String pingApi() {
		if (log.isDebugEnabled() && request != null) {
			log.debug("Ping API from "+request.getCurrent().request().remoteAddress());
		}
		return Lson.builder()
				.put("message", "Welcome to the LIQUIDO API")
				.put("version", "3.0")
				.toString();
	}




	/**
	 * For debugging: Log information about the currently logged in user. Extracted from the JWT.
	 * @return a JSON message with the user's email
	 * @throws LiquidoException When user is not logged in (no JWT was passed)
	 */
	@Query
	@Transactional
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)  // <= this already authenticates the JWT claim "groups"
	public String requireUser() throws LiquidoException {
		//log.info("SecurityContext="+ ctx);
		//log.info("SecurityContext.getUserPrincipal()" + ctx.getUserPrincipal());
		log.info("VertexRequest="+request);
		//log.info("SecurityIdentity="+securityIdentity);
		log.info("JsonWebToken="+jwt);
		log.info("jwt.getName="+jwt.getName());  // UPN
		String email = jwt.getSubject();
		log.info("jwt.subject="+email);  // user's email

		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "Valid JWT but user email not found in DB."));

		log.info("requireUser(): currentUser = " + currentUser);

		return "{\"message\": \"Hello " + email + "\" }";
	}

	@Query
	@RolesAllowed("LIQUIDO_USER")
	@Description("Login with an existing and valid JWT")
	public TeamDataResponse loginWithJwt() throws LiquidoException {
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "Valid JWT but user email not found in DB."));
		log.info("loginWithJwt(): currentUser = " + currentUser);
		return jwtTokenUtils.doLoginInternal(currentUser, null);
	}


	/**
	 * Before the Authy app can be used for login, the authy factor, ie. the Authy Mobile App, must be verified once.
	 * The user MUST be logged in for this call and provide a TOTP from the Authy App.
	 *
	 * @param authToken first auth token (TOTP) from the Authy mobile app
	 * @return success message or HTTP 401 error
	 * @throws LiquidoException if user for JWT is not found
	 */
	@Mutation
	@Description("Verify a new factor for authentication, ie. the Authy Mobile App. User must be logged in and must send one first TOTP from the authy app.")
	@Transactional
	@RolesAllowed("LIQUIDO_USER")
	public String verifyAuthyFactor(
			@Name("authToken") @Description("Time based one time password (TOTP) from the Authy mobile app") String authToken
	) throws LiquidoException {
		String email = jwt.getSubject();    //DOES NOT WORK: securityContext.getUserPrincipal().getName();
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot verify authy Factor. No user with that email!"));
		boolean verified = twilioVerifyClient.verifyFactor(user, authToken);
		if (verified) {
			return "{ \"message\": \"Authy Factor successfully verified\" }";
		} else {
			throw new LiquidoException(Errors.UNAUTHORIZED, "Cannot verifyAuthyFactor. Invlaid authToken.");
		}
	}

	/**
	 * Login with a token from the Authy app
	 * @param email registered user
	 * @param authToken 6-digit token from Authy app
	 * @return TeamDataResponse with team, user and JWT
	 * @throws LiquidoException when email is not found or authToken is invalid
	 */
	@Query
	@PermitAll
	@Description("Login with a one time token from the Authy mobile app.")
	public TeamDataResponse loginWithAuthyToken(
			@Name("email") String email,
			@Name("authToken") String authToken
	) throws LiquidoException {
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot login with Authy token. No user with that email!"));
		boolean approved = twilioVerifyClient.loginWithAuthyToken(user, authToken);
		if (!approved) throw new LiquidoException(Errors.CANNOT_LOGIN_TOKEN_INVALID, "Cannot login. AuthToken is invalid");
		return jwtTokenUtils.doLoginInternal(user, null);
	}

	@Query
	@Description("Request a login link via email.")
	@Transactional
	public String requestEmailToken(@Name("email") String email) throws LiquidoException {
		String emailLowerCase = DoogiesUtil.cleanEmail(email);
		UserEntity user = UserEntity.findByEmail(emailLowerCase)
				.orElseThrow(() -> {
					log.warn("Email " + emailLowerCase + " tried to request email token, but no registered user with that email.");
					return new LiquidoException(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "User with that email <" + emailLowerCase + "> is not found.");
				});

		// Create new email login link with a token time token in it.
		UUID tokenUUID = UUID.randomUUID();
		LocalDateTime validUntil = LocalDateTime.now().plusHours(config.loginLinkExpirationHours());
		OneTimeToken oneTimeToken = new OneTimeToken(tokenUUID.toString(), user, validUntil);
		oneTimeToken.persist();
		log.info("User " + user.getEmail() + " may login via email link.");

		// This link is parsed in a cypress test case. Must update test if you change this.
		String loginLink = "<a id='loginLink' style='font-size: 20pt;' href='" + config.frontendUrl() + "/login?email=" + user.getEmail() + "&emailToken=" + oneTimeToken.getNonce() + "'>Login " + user.getName() + "</a>";
		String body = String.join(
				System.getProperty("line.separator"),
				"<html><h1>Liquido Login Token</h1>",
				"<h3>Hello " + user.getName() + "</h3>",
				"<p>With this link you can login to Liquido.</p>",
				"<p>&nbsp;</p>",
				"<b>" + loginLink + "</b>",
				"<p>&nbsp;</p>",
				"<p>This login link can only be used once!</p>",
				"<p style='color:grey; font-size:10pt;'>You received this email, because a login token for the <a href='https://www.liquido.net'>LIQUIDO</a> eVoting webapp was requested. If you did not request a login yourself, than you may simply ignore this message.</p>",
				"</html>"
		);

		try {
			mailer.send(Mail.withHtml(emailLowerCase, "Login Link for LIQUIDO", body).setFrom("info@liquido.vote"));
		} catch (Exception e) {
			throw new LiquidoException(Errors.CANNOT_LOGIN_INTERNAL_ERROR, "Internal server error: Cannot send Email: " + e, e);
		}
		return "{ \"message\": \"Email successfully sent.\" }";
	}

	@Query
	@Transactional
	public TeamDataResponse loginWithEmailToken(
			@Name("email") String email,
			@Name("authToken") String authToken
	) throws LiquidoException {
		email = DoogiesUtil.cleanEmail(email);
		OneTimeToken ott = OneTimeToken.findByNonce(authToken)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_TOKEN_INVALID, "This email token is invalid!"));
		if (LocalDateTime.now().isAfter(ott.getValidUntil())) {
			ott.delete();
			throw new LiquidoException(Errors.CANNOT_LOGIN_TOKEN_INVALID, "Token is expired!");
		}
		if (!DoogiesUtil.isEqual(email, ott.getUser().getEmail()))
			throw new LiquidoException(Errors.CANNOT_LOGIN_TOKEN_INVALID, "This token is not valid for that email!");

		ott.delete();
		TeamEntity team = TeamEntity.findById(ott.getUser().getLastTeamId());  // team maybe null!
		return jwtTokenUtils.doLoginInternal(ott.getUser(), team);
	}

	/**
	 * Login used during development and in tests.
	 * You MUST pass a valid devLoginToken, then this query will return a valid TeamDataResponse including a JWT for login.
	 * @param devLoginToken the secret devLoginToken
	 * @param email a registered email
	 * @return TeamDataResponse logged into user's last team
	 * @throws LiquidoException when email is not found in DB
	 */
	@Query
	public TeamDataResponse devLogin(
			@Name("devLoginToken") String devLoginToken,
			@Name("email") String email
			//TODO:  @Name("team") Optional<Long> teamId   // optional
	) throws LiquidoException {
		if (!devLoginToken.equals(config.devLoginToken()))
			throw new LiquidoException(Errors.CANNOT_LOGIN_TOKEN_INVALID, "Invalid devLoginToken");
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot do devLogin. Email not found: "+email));
		log.info("DevLogin: "+user.toStringShort());
		return jwtTokenUtils.doLoginInternal(user, null);
	}



}