package org.liquido.team;

import io.smallrye.common.constraint.NotNull;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.*;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.PasswordService;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.liquido.util.LiquidoException.Errors;

@Slf4j
@GraphQLApi
public class TeamGraphQL {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	/** Json Web Token that has been sent with request. */
	@Inject
	JsonWebToken jwt;

	@Inject
	PasswordService passwordService;

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

	// small side note: The login, createTeam or joinTeam requests all return exactly the same response format! I like!


	@Mutation
	@Description("Create a new team. If you call this anonymously a new user will be registered. If you call this with a JWT, you must provide the matching data of your already registered user.")
	@Transactional
	@PermitAll
	public TeamDataResponse createNewTeam(
			@Name("teamName") @NonNull String teamName,
			@Name("admin")  @NonNull UserEntity admin,
			@Name("password")  @NonNull String plainPassword
	) throws LiquidoException {
		admin.setMobilephone(DoogiesUtil.cleanMobilephone(admin.mobilephone));
		admin.setEmail(DoogiesUtil.cleanEmail(admin.email));

		// IF team with same name exist, then throw error
		if (TeamEntity.findByTeamName(teamName).isPresent())
			throw new LiquidoException(Errors.TEAM_WITH_SAME_NAME_EXISTS, "Cannot create new team: A team with that name ('" + teamName + "') already exists");

		// IF user already exists, and is already logged in, then use that user. He wants to create yet another team.
		Optional<UserEntity> currentUserOpt = jwtTokenUtils.getCurrentUser();

		if (currentUserOpt.isEmpty()) {
         /* REGISTER A NEW USER
            GIVEN an anonymous request (this is what normally happens when a new team is created)
             WHEN an anonymous user wants to create a new team
              BUT another user with that email or mobile phone number already exists,
             THEN throw an error   */
			boolean emailExists = UserEntity.findByEmail(admin.email).isPresent();
			boolean mobilePhoneExists = UserEntity.findByMobilephone(admin.mobilephone).isPresent();
			if (emailExists) throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Sorry, another user with that email already exists.");
			if (mobilePhoneExists) throw new LiquidoException(Errors.USER_MOBILEPHONE_EXISTS, "Sorry, another user with that mobile phone number already exists.");

			String hashedPassword = passwordService.hashPassword(plainPassword);
			admin.setPasswordHash(hashedPassword);
			admin.persist();   // New user. This will set the ID on the "admin" entity.
		} else {
          /* CREATE A NEW TEAM WITH AN ALREADY EXISTING AND AUTHENTICATED USER
             GIVEN an authenticated request
              WHEN an already registered user wants to create yet another new team
               BUT they do NOT provide their already registered email, mobile-phone and password
              THEN throw an error */
			UserEntity currentUser = currentUserOpt.get();
			boolean providedOwnDataMatches =
					DoogiesUtil.isEqual(currentUser.email, admin.email) &&
					DoogiesUtil.isEqual(currentUser.mobilephone, admin.mobilephone) &&
					passwordService.verifyPassword(plainPassword, currentUser.passwordHash);

			if (!providedOwnDataMatches) {
				throw new LiquidoException(Errors.CANNOT_CREATE_TEAM_ALREADY_REGISTERED,
						"Your are already registered as " + currentUserOpt.get().email + ". You MUST exactly provide your existing user data for the admin of the new team!");
			}
			admin = currentUserOpt.get();  // already has a DB ID
		}

		// Create a new auth Factor
		//TODO: twilioVerifyClient.createFactor(admin);  //  After a factor has been created, it must still be verified

		jwtTokenUtils.setCurrentUserAndTeam(admin, null);   //BUGFIX: Also set createdBy in TeamEntity
		TeamEntity team = new TeamEntity(teamName, admin);
		team.persist();

		log.info("CREATE NEW TEAM: " + team);
		return jwtTokenUtils.doLoginInternal(admin, team);
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
	//TODO: @PermitAll  Do I need it? Works without.
	public TeamDataResponse joinTeam(
			@Name("inviteCode") @NotNull String inviteCode,
			@Name("member") @NotNull UserEntity member,  //grouped as one argument of type UserModel:  OUTDATED?? https://graphql-rules.com/rules/input-grouping
			@Name("password") String plainPassword
	) throws LiquidoException {
		member.setMobilephone(DoogiesUtil.cleanMobilephone(member.mobilephone));
		member.setEmail(DoogiesUtil.cleanEmail(member.email));
		TeamEntity team = TeamEntity.findByInviteCode(inviteCode)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_JOIN_TEAM_INVITE_CODE_INVALID, "Invalid inviteCode '"+inviteCode+"'"));

		//TODO: make it configurable so that join team requests must be confirmed by an admin first.

		Optional<UserEntity> currentUserOpt = jwtTokenUtils.getCurrentUser();
		if (currentUserOpt.isPresent()) {
			// IF user is already logged in, then he CAN join another team, but he MUST provide his already registered email, mobilephone
			if (!DoogiesUtil.isEqual(currentUserOpt.get().email, member.email) ||
					!DoogiesUtil.isEqual(currentUserOpt.get().mobilephone, member.mobilephone)) {
				throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Your are already registered. You must provide your email and mobilephone to join another team!");
			}
			member = currentUserOpt.get();  // with db ID!
		} else {
			// Anonymous request. Must provide new email and mobilephone
			Optional<UserEntity> userByMail = UserEntity.findByEmail(member.email);
			if (userByMail.isPresent()) throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Cannot JoinTeam: Another user with that email already exists: "+member.email);
			Optional<UserEntity> userByMobilephone = UserEntity.findByMobilephone(member.mobilephone);
			if (userByMobilephone.isPresent()) throw new LiquidoException(Errors.USER_MOBILEPHONE_EXISTS, "Cannot JoinTeam: Another user with that mobile phone number already exists: "+member.mobilephone);
			// set the passwordHash for the new user
			String hashedPassword = passwordService.hashPassword(plainPassword);
			member.setPasswordHash(hashedPassword);
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
	 * Get information about team by inviteCode
	 * @param inviteCode a valid InviteCode
	 * @return TeamEntity or nothing if InviteCode is invalid
	 */
	@Query
	public Optional<TeamEntity> getTeamForInviteCode(
			@Name("inviteCode") String inviteCode
	) {
		return TeamEntity.findByInviteCode(inviteCode);
	}


}