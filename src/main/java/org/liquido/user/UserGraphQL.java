package org.liquido.user;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.OneTimeToken;
import org.liquido.services.TwilioVerifyClient;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.liquido.util.LiquidoException.Errors;

@GraphQLApi
@Slf4j
public class UserGraphQL {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	Mailer mailer;

	/* DOES NOT WORK
	@Context
	SecurityContext securityContext;

	@Inject
	io.smallrye.graphql.api.Context smallryeContext;

	@Inject
	CurrentVertxRequest request;

	// QuarkusSecurityIdentity can directly be injected. Roles are already set from JWT claims "groups".
	// ((QuarkusHttpUser)request.getCurrent().user()).getSecurityIdentity()
	// See docu https://quarkus.io/guides/security-oidc-bearer-token-authentication-tutorial
	@Inject
	SecurityIdentity securityIdentity;
	 */

	/** Json Web Token that has been sent with request. */
	@Inject
	JsonWebToken jwt;

	@Inject
	TwilioVerifyClient twilioVerifyClient;

	@Inject
	LiquidoConfig config;

	@Query
	@Description("Ping for API")
	public String ping() {
		return "Liquido GraphQL API";
	}

	@Context
	SecurityContext ctx;

	@Query
	@Transactional
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)  // <= this already authenticates the JWT claim "groups"
	public String requireUser() throws LiquidoException {
		log.info("SecurityContext="+ ctx);
		//log.info("VertexRequest="+request);
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


	/**
	 * Before the Authy app can be used for login, the authy factor must be verified once.
	 * User MUST be logged in for this verify call!
	 * @param authToken first auth token from the Authy mobile app
	 * @return success message or HTTP 401 error
	 * @throws LiquidoException if user for jwetis not found
	 */
	@Mutation
	@Description("Verify a new Authy Factor. User needs to enter a first one time token.")
	@Transactional
	@RolesAllowed("LIQUIDO_USER")
	public String verifyAuthyFactor(
			@Name("authToken") String authToken
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
		return doLoginInternal(user, null);
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
		return doLoginInternal(ott.getUser(), team);
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
			//TODO: @Name("team") Long teamId   // optional
	) throws LiquidoException {
		if (!devLoginToken.equals(config.devLoginToken()))
			throw new LiquidoException(Errors.CANNOT_LOGIN_TOKEN_INVALID, "Invalid devLoginToken");
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot do devLogin. Email not found: "+email));
		log.info("DevLogin: "+user.toStringShort());
		return doLoginInternal(user, null);
	}


	/**
	 * Login a user into his team and generate a JWT token.
	 * If team is not given and user is member of multiple teams, then he will be logged into the last one he was using,
	 * or otherwise the first team in his list.
	 * @param user a user that wants to log in
	 * @param team (optional) the team to log in. A user can be member in several teams.
	 *             If team is not provided, then user will be logged into his last team.
	 * @return CreateOrJoinTeamResponse
	 * @throws LiquidoException when user has no teams (which should never happen)
	 *   or when user with that email is not member of this team
	 */
	private TeamDataResponse doLoginInternal(UserEntity user, TeamEntity team) throws LiquidoException {
		if (team == null) {
			List<TeamEntity> teams = TeamMemberEntity.findTeamsByMember(user);
			if (teams.size() == 0) {
				log.warn("User ist not member of any team. This should not happen: " + user);
				throw new LiquidoException(Errors.UNAUTHORIZED, "Cannot login. User is not member of any team " + user);
			} else if (teams.size() == 1) {
				team = teams.get(0);
			} else {
				team = teams.stream().filter(t -> t.id == user.lastTeamId).findFirst().orElse(teams.get(0));
			}
		}
		if (team.getMemberByEmail(user.email, null).isEmpty()) {
			throw new LiquidoException(Errors.UNAUTHORIZED, "Cannot login. User is not member of this team! " + user);
		}
		user.setLastLogin(LocalDateTime.now());
		user.setLastTeamId(team.getId());
		user.persist();
		log.debug("Login " + user.toStringShort() + " into team '" + team.getTeamName() + "'");
		String jwt = jwtTokenUtils.generateToken(user.email, team.id, team.isAdmin(user));
		//TODO: authenticateInSecurityContext(user.getId(), team.getId(), jwt);
		return new TeamDataResponse(team, user, jwt);
	}



}