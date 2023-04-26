package org.liquido.graphql;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.security.Authenticated;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.graphql.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.OneTimeToken;
import org.liquido.services.TwilioVerifyClient;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMember;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoException;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@GraphQLApi
@Slf4j
public class LiquidoGraphQL {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	Mailer mailer;

	@Context
	SecurityContext securityContext;

	@Inject
	TwilioVerifyClient twilioVerifyClient;

	@ConfigProperty(name = "liquido.frontendUrl")
	String frontendUrl;

	@ConfigProperty(name = "liquido.loginLinkExpirationHours")
	Long loginLinkExpirationHours;

	@Query
	@Description("Ping for API")
	public String ping() {
		return "Liquido GraphQL API";
	}

	@Query
	@Authenticated
	//@RolesAllowed("LIQUIDO_USER")
	public String requireUser() {

		//String email = securityContext.getUserPrincipal().getName();
		return "{\"message\": \"super\" }";
	}

	@Mutation
	@Description("Register a new user and and create a new team")
	@Transactional
	@PermitAll
	public TeamDataResponse createNewTeam(
			@Name("teamName") String teamName,
			@Name("admin") UserEntity admin
	) throws LiquidoException {
		admin.setMobilephone(cleanMobilephone(admin.mobilephone));
		admin.setEmail(cleanEmail(admin.email));

		// IF team with same name exist, then throw error
		if (TeamEntity.findByTeamName(teamName).isPresent())
			throw new LiquidoException(LiquidoException.Errors.TEAM_WITH_SAME_NAME_EXISTS, "Cannot create new team: A team with that name ('" + teamName + "') already exists");

		Optional<UserEntity> currentUserOpt = Optional.empty();      //TODO: authUtil.getCurrentUserFromDB(); ======================================
		boolean emailExists = UserEntity.findByEmail(admin.email).isPresent();
		boolean mobilePhoneExists = UserEntity.findByMobilephone(admin.mobilephone).isPresent();

		if (!currentUserOpt.isPresent()) {
         /* GIVEN an anonymous request (this is what normally happens when a new team is created)
             WHEN anonymous user wants to create a new team
              AND another user with that email or mobile-phone already exists,
             THEN throw an error   */
			if (emailExists) throw new LiquidoException(LiquidoException.Errors.USER_EMAIL_EXISTS, "Sorry, another user with that email already exists.");
			if (mobilePhoneExists) throw new LiquidoException(LiquidoException.Errors.USER_MOBILEPHONE_EXISTS, "Sorry, another user with that mobile phone number already exists.");
		} else {
          /* GIVEN an authenticated request
              WHEN an already registered user wants to create a another new team
               AND he does NOT provide his already registered email and mobile-phone
               AND he does also NOT provide a completely new email and mobilephone
              THEN throw an error */
			boolean providedOwnData = DoogiesUtil.isEqual(currentUserOpt.get().email, admin.email) && DoogiesUtil.isEqual(currentUserOpt.get().mobilephone, admin.mobilephone);
			if (!providedOwnData && (emailExists || mobilePhoneExists)) {
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CREATE_TEAM_ALREADY_REGISTERED,
						"Your are already registered as " + currentUserOpt.get().email + ". You must provide your existing user data or a new email and new mobile phone for the admin of the new team!");
			} else {
				admin = currentUserOpt.get();  // with db ID!
			}
		}

		// Create a new auth Factor
		admin.persistAndFlush();   // This will set the ID on UserEntity admin
		twilioVerifyClient.createFactor(admin);

		TeamEntity team = new TeamEntity(teamName, admin);
		team.persist();
		log.info("Created new team: " + team);
		return loginUserIntoTeam(admin, team);
	}

	//TODO: joinTeam

	@Mutation
	@Transactional
	@RolesAllowed("LIQUIDO_USER")
	public String verifyAuthyFactor(
			@Name("authToken") String authToken
	) throws LiquidoException {
		String email = securityContext.getUserPrincipal().getName();
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot verify authy Factor. No user with that email!"));
		boolean verified = twilioVerifyClient.verifyFactor(user, authToken);
		if (verified) {
			return "{ \"message\": \"Authy Factor successfully verified\" }";
		} else {
			return "{ \"message\": \"Could not verify Authy Factor. Invalid authToken\" }";
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
	public TeamDataResponse loginWithAuthyToken(
			@Name("email") String email,
			@Name("authToken") String authToken
	) throws LiquidoException {
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot login with Authy token. No user with that email!"));
		boolean approved = twilioVerifyClient.loginWithAuthToken(user, authToken);
		if (!approved) throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_TOKEN_INVALID, "Cannot login. AuthToken is invalid");
		return loginUserIntoTeam(user, null);
	}

	@Query
	@Transactional
	public String requestEmailToken(@Name("email") String email) throws LiquidoException {
		String emailLowerCase = cleanEmail(email);
		UserEntity user = UserEntity.findByEmail(emailLowerCase)
				.orElseThrow(() -> {
					log.info("Email " + emailLowerCase + " tried to request email token, but no registered user with that email.");
					return new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "User with that email <" + emailLowerCase + "> is not found.");
				});

		// Create new email login link with a token time token in it.
		UUID tokenUUID = UUID.randomUUID();
		LocalDateTime validUntil = LocalDateTime.now().plusHours(loginLinkExpirationHours);
		OneTimeToken oneTimeToken = new OneTimeToken(tokenUUID.toString(), user, validUntil);
		oneTimeToken.persist();
		log.info("User " + user.getEmail() + " may login via email link.");

		// This link is parsed in a cypress test case. Must update test if you change this.
		String loginLink = "<a id='loginLink' style='font-size: 20pt;' href='" + frontendUrl + "/login?email=" + user.getEmail() + "&emailToken=" + oneTimeToken.getNonce() + "'>Login " + user.getName() + "</a>";
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
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_INTERNAL_ERROR, "Internal server error: Cannot send Email " + e.toString(), e);
		}
		return "{ \"message\": \"Email successfully sent.\" }";
	}

	@Query
	public TeamDataResponse loginWithEmailToken(
			@Name("email") String email,
			@Name("authToken") String authToken
	) throws LiquidoException {
		email = cleanEmail(email);
		OneTimeToken ott = OneTimeToken.findByNonce(authToken)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_LOGIN_TOKEN_INVALID, "This email token is invalid!"));
		if (LocalDateTime.now().isAfter(ott.getValidUntil())) {
			ott.delete();
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_TOKEN_INVALID, "Token is expired!");
		}
		if (!DoogiesUtil.isEqual(email, ott.getUser().getEmail()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_TOKEN_INVALID, "This token is not valid for that email!");

		return loginUserIntoTeam(ott.getUser(), null);
	}



	/**
	 * Login a user into his team.
	 * If team is not given and user is member of multiple teams, then he will be logged into the last one he was using,
	 * or otherwise the first team in his list.
	 * @param user a user that wants to log in
	 * @param team (optional) team to login if user is member of several teams.
	 *             If not provided, then user will be logged into his last team.
	 * @return CreateOrJoinTeamResponse
	 * @throws LiquidoException when user has no teams (which should never happen)
	 *   or when user with that email is not member of this team
	 */
	private TeamDataResponse loginUserIntoTeam(UserEntity user, TeamEntity team) throws LiquidoException {
		if (team == null) {
			List<TeamEntity> teams = TeamMember.findTeamsByMember(user);
			if (teams.size() == 0) {
				log.warn("User ist not member of any team. This should not happen: " + user);
				throw new LiquidoException(LiquidoException.Errors.UNAUTHORIZED, "Cannot login. User is not member of any team " + user);
			} else if (teams.size() == 1) {
				team = teams.get(0);
			} else {
				team = teams.stream().filter(t -> t.id == user.lastTeamId).findFirst().orElse(teams.get(0));
			}
		}
		if (team.getMemberByEmail(user.email, null).isEmpty()) {
			throw new LiquidoException(LiquidoException.Errors.UNAUTHORIZED, "Cannot login. User is not member of this team! " + user);
		}

		log.debug("Login " + user.toStringShort() + " into team '" + team.getTeamName() + "'");
		String jwt = jwtTokenUtils.generateToken(user.id, team.id);
		//TODO: authenticateInSecurityContext(user.getId(), team.getId(), jwt);
		return new TeamDataResponse(team, user, jwt);
	}


	/**
	 * Clean mobile phone number: Replace everything except plus('+') and number (0-9).
	 * Specifically spaces will be removed.
	 * This is a very simple thing. Have a look at google phone lib for sophisticated phone number parsing
	 * @param mobile a non formatted phone numer
	 * @return the cleaned up phone number
	 */
	public static String cleanMobilephone(String mobile) {
		if (mobile == null) return null;
		return mobile.replaceAll("[^\\+0-9]", "");
	}

	/**
	 * emails a case IN-sensitive. So store and compare them in lowercase
	 * @param email an email address
	 * @return the email in lowercase
	 */
	public static String cleanEmail(String email) {
		if (email == null) return null;
		return email.toLowerCase();
	}
}