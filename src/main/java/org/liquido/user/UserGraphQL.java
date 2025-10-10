package org.liquido.user;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.PasswordServiceBcrypt;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.twillio.TwilioVerifyClient;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;
import org.liquido.util.Lson;

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
	UserService userService;



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
	@PermitAll
	public String pingApi() {
		if (log.isDebugEnabled() && request != null) {
			log.debug("Ping API from {}", request.getCurrent().request().remoteAddress());
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
		log.info("VertexRequest={}", request);
		//log.info("SecurityIdentity="+securityIdentity);
		log.info("JsonWebToken={}", jwt);
		log.info("jwt.getName={}", jwt.getName());  // UPN
		String email = jwt.getSubject();
		log.info("jwt.subject={}", email);  // user's email

		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "Valid JWT but user email not found in DB."));

		log.info("requireUser(): currentUser = " + currentUser);

		return "{\"message\": \"Hello " + email + "\" }";
	}

	@Query
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Description("Login with an existing and valid JWT. The user that is encoded in the JWT must exist. Then this will return a NEW updated JWT!")
	public TeamDataResponse loginWithJwt() throws LiquidoException {
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supplyAndLog(Errors.UNAUTHORIZED, "Valid JWT but user XXX email not found in DB."));
		log.info("loginWithJwt(): currentUser = {}", currentUser);
		return jwtTokenUtils.doLoginInternal(currentUser, null);
	}


	@Query
	@Description("Standard login with email and password")
	public TeamDataResponse loginWithEmailPassword(
			@Name("email") @NonNull String email,
			@Name("password") @NonNull String plainPassword
	) throws LiquidoException {
		email = DoogiesUtil.cleanEmail(email);
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(() -> new LiquidoException(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot login. There is no register user with that email."));
		boolean verified = PasswordServiceBcrypt.verifyPassword(plainPassword, user.getPasswordHash());
		if (verified) {
			TeamEntity team = TeamEntity.findById(user.getLastTeamId());  // team maybe null!
			log.debug("LOGIN via email link: " + user.toStringShort());
			return jwtTokenUtils.doLoginInternal(user, team);
		} else {
			throw new LiquidoException(Errors.UNAUTHORIZED, "Cannot login. Password is invalid");
		}
	}


	@Query
	@Description("Request a password reset. Will send a mail with a link where user can reset his password.")
	@Transactional
	public String requestPasswordReset(
			@Description("Must be a registered mail.") @Name("email") @NonNull String email
	) throws LiquidoException {
		return userService.requestPasswordReset(email);
	}



	@Query
	@Description("Reset a user's password. Needs valid one time token.")
	@Transactional
	public String resetPassword(
			@Description("Must be a registered mail.") @Name("email") @NonNull String email,
			@Description("The OTT user received from requestPasswordReset") @NonNull String resetPasswordToken,
			@NonNull String newPassword
	) throws LiquidoException {
		return userService.resetPassword(email, resetPasswordToken, newPassword);
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
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public String verifyAuthyFactor(
			@Name("authToken") @NonNull @Description("Time based one time password (TOTP) from the Authy mobile app") String authToken
	) throws LiquidoException {
		String email = jwt.getSubject();    //DOES NOT WORK: securityContext.getUserPrincipal().getName();
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot verify authy Factor. No user with that email!"));
		boolean verified = twilioVerifyClient.verifyFactor(user, authToken);
		if (verified) {
			return "{ \"message\": \"Authy Factor successfully verified\" }";
		} else {
			throw new LiquidoException(Errors.UNAUTHORIZED, "Cannot verifyAuthyFactor. Invalid authToken.");
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
			@Name("email") @NonNull String email,
			@Name("authToken") @NonNull String authToken
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
	public String requestEmailLoginLink(@Name("email") String email) throws LiquidoException {
		return userService.requestEmailLoginLink(email);
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
	@PermitAll
	public TeamDataResponse devLogin(
			@Name("devLoginToken") String devLoginToken,
			@Name("email") String email
			//TODO:  @Name("team") Optional<Long> teamId   // optional
	) throws LiquidoException {
		if (!devLoginToken.equals(config.devLoginToken()) || LaunchMode.current() == LaunchMode.NORMAL)
			throw new LiquidoException(Errors.CANNOT_LOGIN_TOKEN_INVALID, "Invalid devLoginToken or in normal/prod LaunchMode.");
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot do devLogin. Email not found: "+email));
		log.info("DevLogin: "+user.toStringShort());
		return jwtTokenUtils.doLoginInternal(user, null);
	}



}