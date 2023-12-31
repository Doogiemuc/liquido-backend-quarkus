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

		Optional<UserEntity> currentUserOpt = jwtTokenUtils.getCurrentUser();
		boolean emailExists = UserEntity.findByEmail(admin.email).isPresent();
		boolean mobilePhoneExists = UserEntity.findByMobilephone(admin.mobilephone).isPresent();

		if (currentUserOpt.isEmpty()) {
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
		//TODO: twilioVerifyClient.createFactor(admin);  //  After a factor has been created, it must still be verified

		admin.persist();   // This will set the ID on UserEntity admin
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
			@Name("member") @NotNull UserEntity member  //grouped as one argument of type UserModel: https://graphql-rules.com/rules/input-grouping
	) throws LiquidoException {
		member.setMobilephone(DoogiesUtil.cleanMobilephone(member.mobilephone));
		member.setEmail(DoogiesUtil.cleanEmail(member.email));
		TeamEntity team = TeamEntity.findByInviteCode(inviteCode)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_JOIN_TEAM_INVITE_CODE_INVALID, "Invalid inviteCode '"+inviteCode+"'"));

		//TODO: make it configurable so that join team requests must be confirmed by an admin first.

		Optional<UserEntity> currentUserOpt = jwtTokenUtils.getCurrentUser();
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
			if (userByMail.isPresent()) throw new LiquidoException(Errors.USER_EMAIL_EXISTS, "Cannot JoinTeam: Another user with that email already exists: "+member.email);
			Optional<UserEntity> userByMobilephone = UserEntity.findByMobilephone(member.mobilephone);
			if (userByMobilephone.isPresent()) throw new LiquidoException(Errors.USER_MOBILEPHONE_EXISTS, "Cannot JoinTeam: Another user with that mobile phone number already exists: "+member.mobilephone);
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