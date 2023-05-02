package org.liquido.graphql;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.smallrye.common.constraint.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.OneTimeToken;
import org.liquido.services.TwilioVerifyClient;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMember;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoException;
import static org.liquido.util.LiquidoException.Errors;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
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
	//@Authenticated
	@RolesAllowed("LIQUIDO_USER")  // <= this already authenticates the JWT claim "groups"
	public String requireUser() {
		//log.info("SecurityContext=" + securityContext);
		//log.info("Context="+ smallryeContext);
		//log.info("VertexRequest="+request);
		//log.info("SecurityIdentity="+securityIdentity);
		log.info("JsonWebToken="+jwt);
		log.info("name="+jwt.getName());  // UPN
		String email = jwt.getSubject();
		log.info("jwt.subject="+email);  // user's email
		return "{\"message\": \"Hello " + email + "\" }";
	}

	@Mutation
	@Description("Register a new user and and create a new team")
	@Transactional
	@PermitAll
	public TeamDataResponse createNewTeam(
			@Name("teamName") String teamName,
			@Name("admin") UserEntity admin
	) throws LiquidoException {
		admin.setMobilephone(DoogiesUtil.cleanMobilephone(admin.mobilephone));
		admin.setEmail(DoogiesUtil.cleanEmail(admin.email));

		// IF team with same name exist, then throw error
		if (TeamEntity.findByTeamName(teamName).isPresent())
			throw new LiquidoException(Errors.TEAM_WITH_SAME_NAME_EXISTS, "Cannot create new team: A team with that name ('" + teamName + "') already exists");

		Optional<UserEntity> currentUserOpt = Optional.empty();      //TODO: authUtil.getCurrentUserFromDB(); ======================================
		boolean emailExists = UserEntity.findByEmail(admin.email).isPresent();
		boolean mobilePhoneExists = UserEntity.findByMobilephone(admin.mobilephone).isPresent();

		if (!currentUserOpt.isPresent()) {
         /* GIVEN an anonymous request (this is what normally happens when a new team is created)
             WHEN anonymous user wants to create a new team
              AND another user with that email or mobile-phone already exists,
             THEN throw an error   */
			if (emailExists) throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Sorry, another user with that email already exists.");
			if (mobilePhoneExists) throw new LiquidoException(Errors.USER_MOBILEPHONE_EXISTS, "Sorry, another user with that mobile phone number already exists.");
		} else {
          /* GIVEN an authenticated request
              WHEN an already registered user wants to create a another new team
               AND he does NOT provide his already registered email and mobile-phone
               AND he does also NOT provide a completely new email and mobilephone
              THEN throw an error */
			boolean providedOwnData = DoogiesUtil.isEqual(currentUserOpt.get().email, admin.email) && DoogiesUtil.isEqual(currentUserOpt.get().mobilephone, admin.mobilephone);
			if (!providedOwnData && (emailExists || mobilePhoneExists)) {
				throw new LiquidoException(Errors.CANNOT_CREATE_TEAM_ALREADY_REGISTERED,
						"Your are already registered as " + currentUserOpt.get().email + ". You must provide your existing user data or a new email and new mobile phone for the admin of the new team!");
			} else {
				admin = currentUserOpt.get();  // with db ID!
			}
		}

		// Create a new auth Factor
		admin.persistAndFlush();   // This will set the ID on UserEntity admin
		//TODO: twilioVerifyClient.createFactor(admin);  //  After a factor has been created, it must still be verified

		TeamEntity team = new TeamEntity(teamName, admin);
		team.persist();
		log.info("Created new team: " + team);
		return loginUserIntoTeam(admin, team);
	}

	/**
	 * Join an existing team as a member.
	 *
	 * This should be called anonymously. Then the new member <b>must</b> register with a an email and mobilephone that does not exit in LIQUIDO yet.
	 * When called with JWT, then the already registered user may join this additional team. But he must exactly provide his user data.
	 * Will also throw an error, when email is already admin or member in that team.
	 *
	 * After returning from this method, the user will be logged in.
	 *
	 * @param inviteCode valid invite code of the team to join
	 * @param member new user member, with email and mobilephone
	 * @return Info about the joined team and a JsonWebToken
	 * @throws LiquidoException when inviteCode is invalid, or when this email is already admin or member in team.
	 */
	@Transactional
	@Mutation()
	@Description("Join an existing team with an inviteCode")
	public TeamDataResponse joinTeam(
			@Name("inviteCode") @NotNull String inviteCode,
			@Name("member") @NotNull UserEntity member  //grouped as one argument of type UserModel: https://graphql-rules.com/rules/input-grouping
	) throws LiquidoException {
		member.setMobilephone(DoogiesUtil.cleanMobilephone(member.mobilephone));
		member.setEmail(DoogiesUtil.cleanEmail(member.email));
		TeamEntity team = TeamEntity.findByInviteCode(inviteCode)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_JOIN_TEAM_INVITE_CODE_INVALID, "Invalid inviteCode '"+inviteCode+"'"));

		//TODO: make it configurable so that join team requests must be confirmed by an admin first.

		Optional<UserEntity> currentUserOpt = getCurrentUserFromDB();
		if (currentUserOpt.isPresent()) {
			// IF user is already logged in, then he CAN join another team, but he MUST provide his already registered email and mobilephone.
			if (!DoogiesUtil.isEqual(currentUserOpt.get().email, member.email) ||
					!DoogiesUtil.isEqual(currentUserOpt.get().mobilephone, member.mobilephone)) {
				throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Your are already registered. You must provide your email and mobilephone to join another team!");
			}
			member = currentUserOpt.get();  // with db ID!
		} else {
			// Anonymous request. Must provide new email and mobilephone
			Optional<UserEntity> userByMail = UserEntity.findByEmail(member.email);
			if (userByMail.isPresent()) throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Sorry, another user with that email already exists.");
			Optional<UserEntity> userByMobilephone = UserEntity.findByMobilephone(member.mobilephone);
			if (userByMobilephone.isPresent()) throw new LiquidoException(Errors.USER_MOBILEPHONE_EXISTS, "Sorry, another user with that mobile phone number already exists.");
		}

		try {
			member.persist();
			team.addMember(member, TeamMember.Role.MEMBER);   // Add to java.util.Set. Will never add duplicate.
			team.persist();
			log.info("User <" + member.email + "> joined team: " + team.toString());
			String jwt = jwtTokenUtils.generateToken(member.email, team.id);
			//BUGFIX: Authenticate new user in spring's security context, so that access restricted attributes such as isLikeByCurrentUser can be queried via GraphQL.
			//authUtil.authenticateInSecurityContext(member.id, team.id, jwt);
			return new TeamDataResponse(team, member, jwt);
		} catch (Exception e) {
			throw new LiquidoException(Errors.INTERNAL_ERROR, "Error: Cannot join team.", e);
		}
	}


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
	@Description("Login with a one time token from the Authy mobile app.")
	public TeamDataResponse loginWithAuthyToken(
			@Name("email") String email,
			@Name("authToken") String authToken
	) throws LiquidoException {
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot login with Authy token. No user with that email!"));
		boolean approved = twilioVerifyClient.loginWithAuthToken(user, authToken);
		if (!approved) throw new LiquidoException(Errors.CANNOT_LOGIN_TOKEN_INVALID, "Cannot login. AuthToken is invalid");
		return loginUserIntoTeam(user, null);
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
			throw new LiquidoException(Errors.CANNOT_LOGIN_INTERNAL_ERROR, "Internal server error: Cannot send Email " + e.toString(), e);
		}
		return "{ \"message\": \"Email successfully sent.\" }";
	}

	@Query
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

		log.debug("Login " + user.toStringShort() + " into team '" + team.getTeamName() + "'");
		String jwt = jwtTokenUtils.generateToken(user.email, team.id);
		//TODO: authenticateInSecurityContext(user.getId(), team.getId(), jwt);
		return new TeamDataResponse(team, user, jwt);
	}

	public Optional<UserEntity> getCurrentUserFromDB() {
		if (jwt == null || DoogiesUtil.isEmpty(jwt.getName())) return Optional.empty();
		String email = jwt.getName();
		return UserEntity.findByEmail(email);
	}

}