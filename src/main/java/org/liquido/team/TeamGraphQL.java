package org.liquido.team;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.PasswordServiceBcrypt;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;
import org.liquido.vote.RightToVoteEntity;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.liquido.util.LiquidoException.Errors;

@Slf4j
@GraphQLApi
public class TeamGraphQL {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	LiquidoConfig config;

	/** Json Web Token that has been sent with request. */
	@Inject
	JsonWebToken jwt;


	//The login, createTeam or joinTeam requests all return exactly the same response format! I like!

	/**
	 * Register as a new user and create a new team. The new user will become the admin of the team.
	 *
	 * @param teamName name of the new team
	 * @param admin info about new user
	 * @param plainPassword user's plain password
	 * @return Info about team, admin and JSON web token (JWT)
	 * @throws LiquidoException when a user is already logged in or a team with the same name already happens to exist.
	 */
	@Mutation
	@Description("Register in LIQUIDO and create a new team.")
	@Transactional
	@PermitAll
	public TeamDataResponse createNewTeam(
			@Name("teamName") @NonNull String teamName,
			/* @Valid */ @Name("admin")  @NonNull UserEntity admin,     //TODO: is valid necessary or is UserEntity automatically validated by GraphQL lib? Probably yes
			@Name("password")  @NonNull String plainPassword
	) throws LiquidoException {
		// IF calling user is already logged in, then he must use addAnotherTeam()
		if (jwtTokenUtils.getCurrentUser().isPresent())
			throw new LiquidoException(Errors.CANNOT_CREATE_TEAM_ALREADY_REGISTERED, "You are already registered. Call the GraphQl mutation 'addAnotherTeam()' !");

		// IF team with same name exist, then throw error
		if (TeamEntity.findByTeamName(teamName).isPresent())
			throw new LiquidoException(Errors.TEAM_WITH_SAME_NAME_EXISTS, "Cannot create new team: A team with that name ('" + teamName + "') already exists. If you have an invite code, you could join that team.");

		createNewLiquidoUser(admin, plainPassword);
		TeamEntity team = createNewTeam(teamName, admin);
		return jwtTokenUtils.doLoginInternal(admin, team);
	}


	/**
	 * An already logged in user that is already a member of a team can also create another team.
	 * @param teamName name of the new team
	 * @return login info
	 * @throws LiquidoException when the user is not logged in
	 */
	@Mutation
	@Description("Add another team. This is only for already registered users.")
	@Transactional
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public TeamDataResponse addAnotherTeam(
			@Name("teamName") @NonNull String teamName
	) throws LiquidoException {
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "You must be logged in to addAnotherTeam. Use mutation 'createNewTeam' to register as a new user!"));
		TeamEntity team = createNewTeam(teamName, currentUser);
		log.info("addAnotherTeam: {}", team);
		return jwtTokenUtils.doLoginInternal(currentUser, team);
	}

	/**
	 * <h1>Switch into another team</h1>
	 *
	 * One user can be a member of several teams, but a session is always scoped to exactly one of
	 * them - the {@code teamId} JWT claim is what every team-scoped lookup compares against. This
	 * mutation issues a new JWT for a different team of the same user.
	 *
	 * <p>Only an <b>already logged-in</b> user may switch, which is what {@code @RolesAllowed} here
	 * enforces. Note that a {@code LIQUIDO_POLLY} token deliberately does not carry
	 * {@code LIQUIDO_USER_ROLE}, so a polly session cannot reach this - there is no UserEntity behind
	 * one anyway.
	 *
	 * <p>There is intentionally <b>no</b> separate ownership check here.
	 * {@link JwtTokenUtils#doLoginInternal} already refuses a team the user is not a member of with
	 * {@code CANNOT_LOGIN_USER_NOT_MEMBER_OF_TEAM} - the very same guarantee that lets {@code devLogin}
	 * accept a caller-supplied teamId. Keeping that decision in one place is the point; a second,
	 * parallel check here would be one more thing that can drift.
	 *
	 * <p>{@code doLoginInternal} also writes {@code lastTeamId}, so the choice survives to the next login.
	 *
	 * @param teamId id of a team that the current user is a member (or admin) of
	 * @return the same login payload as any other login, now scoped to the new team
	 * @throws LiquidoException when the team does not exist, or the user is not a member of it
	 */
	@Mutation
	@Description("Switch into another team. The currently logged in user must be a member of that team.")
	@Transactional
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public TeamDataResponse switchTeam(
			@Name("teamId") @NonNull Long teamId
	) throws LiquidoException {
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "You must be logged in to switch teams."));
		TeamEntity team = TeamEntity.<TeamEntity>findByIdOptional(teamId)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_TEAM_NOT_FOUND, "Cannot switch team. Team(id=" + teamId + ") not found."));
		log.info("SWITCH TEAM: {} into team '{}'", currentUser.toStringShort(), team.getTeamName());
		return jwtTokenUtils.doLoginInternal(currentUser, team);
	}

	/**
	 * <h1>Join an existing team as a member</h1>
	 *
	 * This should be called anonymously. Then the new member <b>must</b> register with a an email and mobilephone that does not exit in LIQUIDO yet.
	 * When called with JWT, then the already registered user may join this additional team. But he must exactly provide his user data.
	 * Will also throw an error, when email is already admin or member in that team.
	 *
	 * @param inviteCode valid invite code of the team to join
	 * @param member new user member, with email and mobilephone
	 * @return Info about the joined team and a JsonWebToken
	 * @throws LiquidoException when inviteCode is invalid, or when this email is already admin or member in team.
	 */
	@Transactional
	@Mutation
	@Description("Join an existing team with an inviteCode")
	public TeamDataResponse joinTeam(
			@Name("inviteCode") @NonNull String inviteCode,
			@Name("member") @NonNull UserEntity member,
			@Name("password") @NonNull String plainPassword
	) throws LiquidoException {
		member.setMobilephone(DoogiesUtil.cleanMobilephone(member.mobilephone));
		member.setEmail(DoogiesUtil.cleanEmail(member.email));
		TeamEntity team = TeamEntity.findByInviteCode(inviteCode)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_JOIN_TEAM_INVITE_CODE_INVALID, "Invalid inviteCode '"+inviteCode+"'"));

		//TODO: make it configurable so that join team requests must be confirmed by an admin first.

		Optional<UserEntity> currentUserOpt = jwtTokenUtils.getCurrentUser();
		if (currentUserOpt.isPresent()) {
			// IF user is already logged in, then he CAN join another team, but he MUST prove it is really
			// him by presenting exactly the identity he is already registered with.
			assertProvidedIdentityMatches(currentUserOpt.get(), member);
			member = currentUserOpt.get();  // with db ID!
			//TODO: sanity check RightToVoteEntity.findByVoter(member).orElseThrow(...)   -> Or create a separate RightToVote per team?
		} else {
			// Anonymous request. Must provide new email and mobilephone
			Optional<UserEntity> userByMail = UserEntity.findByEmail(member.email);
			if (userByMail.isPresent()) throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Cannot JoinTeam: Another user with that email already exists: "+member.email);
			Optional<UserEntity> userByMobilephone = UserEntity.findByMobilephone(member.mobilephone);
			if (userByMobilephone.isPresent()) throw new LiquidoException(Errors.USER_MOBILEPHONE_EXISTS, "Cannot JoinTeam: Another user with that mobile phone number already exists: "+member.mobilephone);

			createNewLiquidoUser(member, plainPassword);
		}

		try {
			member.persist();
			team.addMember(member, TeamMemberEntity.Role.MEMBER);   // Add to java.util.Set. Will never add duplicate.
			team.persist();
			member.setLastTeamId(team.getId());
			member.setLastLogin(LocalDateTime.now());
			member.persist();
			log.info("JOIN TEAM " + member.toStringShort() + " joined team: " + team);
			return jwtTokenUtils.doLoginInternal(member, team);
		} catch (Exception e) {
			throw new LiquidoException(Errors.INTERNAL_ERROR, "Error: Cannot join team.", e);
		}
	}

	/**
	 * A logged-in user may join a FURTHER team, but only by presenting exactly the identity they are
	 * already registered with. Joining a team is a trust decision in LIQUIDO - it grants a right to vote
	 * in that team - so this check is deliberately strict: anything we cannot positively confirm as a
	 * match is rejected. We never silently fall back to "close enough".
	 *
	 * <ol>
	 *   <li><b>Email</b> MUST be present on both sides AND be equal. A missing email on either side is
	 *       always a rejection - absence is never treated as a match.</li>
	 *   <li><b>Mobilephone</b> MUST either be absent on BOTH sides, or present on both AND equal.
	 *       Present on only one side is a rejection.</li>
	 * </ol>
	 *
	 * "Absent" means null or blank, via {@link DoogiesUtil#isEmpty}. Blank has to count as absent: the
	 * mobilephone column is nullable, and {@code cleanMobilephone("")} returns {@code ""}, so a client
	 * that sends an empty field means the same thing as one that omits it. Comparing those two forms
	 * directly would reject a user who supplied everything they actually have.
	 *
	 * Both entities are already normalised by the caller (cleanEmail lowercases, cleanMobilephone strips
	 * formatting), so this compares like with like.
	 *
	 * @param registered the user as stored in the DB, resolved from the JWT
	 * @param provided the user data sent with this joinTeam request
	 * @throws LiquidoException when the provided identity does not match the registered one
	 */
	private static void assertProvidedIdentityMatches(UserEntity registered, UserEntity provided) throws LiquidoException {
		// (1) Email: must exist on both sides and match.
		if (DoogiesUtil.isEmpty(registered.email) || DoogiesUtil.isEmpty(provided.email) ||
				!DoogiesUtil.isEqualString(registered.email, provided.email)) {
			throw new LiquidoException(Errors.USER_EMAIL_EXISTS,
					"You are already registered. You must provide your registered email to join another team!");
		}

		// (2) Mobilephone: either absent on both sides, or present on both and equal.
		boolean registeredHasMobilephone = !DoogiesUtil.isEmpty(registered.mobilephone);
		boolean providedHasMobilephone = !DoogiesUtil.isEmpty(provided.mobilephone);
		if (registeredHasMobilephone != providedHasMobilephone ||
				(registeredHasMobilephone && !DoogiesUtil.isEqualString(registered.mobilephone, provided.mobilephone))) {
			throw new LiquidoException(Errors.USER_EMAIL_EXISTS,
					"You are already registered. You must provide your registered mobilephone to join another team!");
		}
	}

	/**
	 * Create a new LIQUIDO user, hash his plainPassword and assign a new RightToVote to him.
	 * @param user new user
	 * @param plainPassword user's password
	 * @throws LiquidoException When another user with same email or mobilephone already exists
	 */
	private void createNewLiquidoUser(UserEntity user, String plainPassword) throws LiquidoException {
		PasswordServiceBcrypt.assertPasswordStrongEnough(plainPassword, config.minPasswordLength());
		// IF a user with that email or mobilephone already exists, throw error.
		user.setMobilephone(DoogiesUtil.cleanMobilephone(user.mobilephone));
		user.setEmail(DoogiesUtil.cleanEmail(user.email));
		if (UserEntity.findByEmail(user.email).isPresent())
			throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Sorry, a user with that email already exists.");
		if (UserEntity.findByMobilephone(user.mobilephone).isPresent())
			throw new LiquidoException(Errors.USER_MOBILEPHONE_EXISTS, "Sorry, a user with that mobile phone number already exists.");

		// Create new user
		log.info("Creating new liquido user {}", user);
		user.setPasswordHash(PasswordServiceBcrypt.hashPassword(plainPassword));   // MUST set passwordHash before persisting UserEntity!
		user.persist();

		RightToVoteEntity rightToVote = RightToVoteEntity.build(user, config.rightToVoteExpirationDays(), config.hashSecret());
		rightToVote.persist();
		log.debug("Created liquido user with RightToVote"+rightToVote);

		// Create a new auth Factor
		//TODO: twilioVerifyClient.createFactor(user);  //  After a factor has been created, it must still be verified
	}

	/**
	 * Create a new LIQUIDO team and set it's admin.
	 * @param teamName new teamName
	 * @param admin admin of the team. Must be an already persisted user
	 * @return the newly created team
	 */
	private TeamEntity createNewTeam(String teamName, UserEntity admin) {
		if (admin.id == null) throw new RuntimeException("cannot createNewTeam. Admin must already be persisted.");
		jwtTokenUtils.setCurrentUserAndTeam(admin, null);   //BUGFIX: Also set createdBy in TeamEntity
		TeamEntity team = new TeamEntity(teamName, admin, config.inviteCodeLength());
		team.persist();
		log.info("CREATE NEW TEAM: {}", team);
		return team;
	}

	/**
	 * Get information about user's own team, including the team's polls.
	 * @return info about user's own team.
	 * @throws LiquidoException when not logged
	 */
	@Query
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Transactional
	public TeamEntity team() throws LiquidoException {
		Long teamId = Long.valueOf(jwt.getClaim(JwtTokenUtils.TEAM_ID_CLAIM));
		Optional<TeamEntity> teamOpt = TeamEntity.findByIdOptional(teamId);
		return teamOpt.orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "Cannot get team. User must be logged into a team!"));
	}

	/**
	 * When a user gets invited to a team with an inviteCode,
	 * then the frontend can fetch the full team info here.
	 * GraphQl: "query { teamForInviteCode("AB12DE34") { ... } }   (remove get- prefix!)
	 * @param inviteCode must be a valid InviteCode
	 * @return the team for this invite code
	 * @throws LiquidoException if invite code is invalid
	 */
	@Query
	@PermitAll
	public TeamEntity getTeamForInviteCode(
			@Name("inviteCode") String inviteCode
	) throws LiquidoException {
		return TeamEntity.findByInviteCode(inviteCode)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_JOIN_TEAM_INVITE_CODE_INVALID, "Invalid invite code!"));
	}


}