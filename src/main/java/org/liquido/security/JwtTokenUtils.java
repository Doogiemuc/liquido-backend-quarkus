package org.liquido.security;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.team.TeamSummary;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class to generate and then validate JsonWebTokens for Liquido.
 * Each JWT contains the user's <b>ID</b> as JWT "subject" claim.
 */
@Slf4j
@RequestScoped
public class JwtTokenUtils {

  // https://quarkus.io/guides/security-authentication-mechanisms-concept#smallrye-jwt-authentication
	// https://quarkus.io/guides/security-architecture-concept
	// https://quarkus.io/guides/security-jwt

	// This MUST match "mp.jwt.verify.issuer" in application.properties!
	public static final String LIQUIDO_ISSUER = "https://liquido.vote";

	public static final String LIQUIDO_USER_ROLE = "LIQUIDO_USER";    // Everyone is a user (also members)
	public static final String LIQUIDO_ADMIN_ROLE = "LIQUIDO_ADMIN";  // but only some are admins!

	/**
	 * A Polly passkey session - somebody identified by nothing but a WebAuthn credential.
	 *
	 * <p>Deliberately does NOT imply {@link #LIQUIDO_USER_ROLE}: a polly token must never open
	 * a team endpoint. There is no UserEntity behind it, so anything calling
	 * {@link #getCurrentUser()} with one of these would find nothing anyway.
	 */
	public static final String LIQUIDO_POLLY_ROLE = "LIQUIDO_POLLY";

	@Inject
	LiquidoConfig config;

	@Inject
	JsonWebToken jwt;

	/** Key for teamId Claim in JWT. Keep in mind that the teamId is stored as a String in JWT claim! */
	public static final String TEAM_ID_CLAIM = "teamId";

	/**
	 * This generates a new JWT. This needs jwtSecret as input, so that only the server can
	 * generate JWTs. The userId becomes the JWT.subject and teamId is set as additional claim.
	 */
	public String generateToken(@NonNull String email, @NonNull Long teamId, boolean isAdmin) {
		Set<String> groups = new HashSet<>();
		groups.add(LIQUIDO_USER_ROLE);
		if (isAdmin) groups.add(LIQUIDO_ADMIN_ROLE);
		return Jwt
				.subject(email)
				//.upn("upn@liquido.vote")  // if upn is set, this will be used instead of subject   see JWTCallerPrincipal.getName()
				.issuer(LIQUIDO_ISSUER)     // this is important. It will be verified by quarkus-security
				.groups(groups)
				.claim(TEAM_ID_CLAIM, String.valueOf(teamId))  // better put strings into claims
				.expiresIn(config.jwt().expirationSecs())
				.sign();  // uses the RS256 keypair configured per-environment (smallrye.jwt.sign.key)
	}

	/**
	 * Generate a Polly session token. The WebAuthn credential id is the whole identity -
	 * there is no user, no team and no email anywhere in a polly.
	 *
	 * <p>Same issuer and signing key as a team token, so smallrye-jwt validates it with no
	 * extra configuration. What differs is the group: {@link #LIQUIDO_POLLY_ROLE} only, so the
	 * token is worthless against every {@code @RolesAllowed(LIQUIDO_USER_ROLE)} endpoint.
	 *
	 * <p>Long lived (~30 days) on purpose: one tap on the first visit and none afterwards is
	 * what lets a polly have no login screen at all.
	 *
	 * @param credentialId the id of a WebAuthn credential that has just been verified
	 * @return a signed polly JWT
	 */
	public String generatePollyToken(@NonNull String credentialId) {
		return Jwt
				.subject(credentialId)
				.issuer(LIQUIDO_ISSUER)
				.groups(Set.of(LIQUIDO_POLLY_ROLE))
				.expiresIn(config.polly().jwtExpirationSecs())
				.sign();
	}



	/** (lazy loaded) currently logged in user */
	private UserEntity currentUser = null;

	/**
	 * The team that this user is currently logged in.
	 * (One user can be member of several teams.)
	 */
	private TeamEntity currentTeam = null;


	/**
	 * Login a user into his team and generate a JWT token.
	 * If team is not given and user is member of multiple teams, then he will be logged into the last one he was using,
	 * or otherwise the first team in his list.
	 *
	 * <p>A team that no longer exists simply is not among the user's memberships any more, so the
	 * "last team was deleted" case needs no special handling: the {@code lastTeamId} filter finds
	 * nothing and the fallback picks the first remaining team. That fallback is only well defined
	 * because {@link TeamMemberEntity#findTeamsByMember} sorts - see the note there.
	 *
	 * <p>Callers should pass {@code team == null} unless they specifically mean to pin one team
	 * (as {@code switchTeam} and {@code devLogin} do). Resolving the team at the call site instead
	 * skips the membership fallback below and turns "user was removed from their last team" into a
	 * failed login rather than a login into one of their other teams.
	 *
	 * @param user a user that wants to log in
	 * @param team (optional) the team to log in. A user can be member in several teams.
	 *             If team is not provided, then user will be logged into his last team.
	 * @return CreateOrJoinTeamResponse, with {@code teams} listing every team of this user
	 * @throws LiquidoException when user has no teams (which should never happen)
	 *   or when user with that email is not member of this team
	 */
	public TeamDataResponse doLoginInternal(UserEntity user, TeamEntity team) throws LiquidoException {
		// Fetched unconditionally, because the response carries the full list for the team switcher,
		// not just the one team we log into.
		List<TeamEntity> teams = TeamMemberEntity.findTeamsByMember(user);
		if (teams.isEmpty()) {
			log.warn("User ist not member of any team. Maybe his team was deleted? {}", user);
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_USER_NOT_MEMBER_OF_TEAM, "Cannot login. User is not member of any team " + user);
		}
		if (team == null) {
			if (teams.size() == 1) {
				team = teams.get(0);
			} else {
				team = teams.stream().filter(t -> t.id == user.lastTeamId).findFirst().orElse(teams.get(0));
			}
		}
		if (team.getMemberByEmail(user.email, null).isEmpty()) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_USER_NOT_MEMBER_OF_TEAM, "Cannot login. User is not member of this team! " + user);
		}
		user.setLastLogin(LocalDateTime.now());
		user.setLastTeamId(team.getId());
		user.persist();
		log.debug("doLoginInternal(): {} into team '{}'", user.toStringShort(), team.getTeamName());
		String jwt = generateToken(user.email, team.id, team.isAdmin(user));
		// MUST programmatically log in the user, because we already need it to create TeamDataResponse.poll.proposal.isCreatedByCurrentUser
		setCurrentUserAndTeam(user, team);
		//TODO: authenticateInSecurityContext(user.getId(), team.getId(), jwt);
		TeamDataResponse res = new TeamDataResponse(team, user, jwt);
		res.teams = teams.stream().map(TeamSummary::of).toList();
		return res;
	}


	/**
	 * Get the currently logged-in liquido user.
	 * The UserEntity will lazily be loaded the first time you call this
	 * and then cached for succeeding calls.
	 * @return Optional.of(UserEntity) or Optional.empty() if not logged in
	 */
	public Optional<UserEntity> getCurrentUser() {
		if (this.currentUser != null) return Optional.of(currentUser);
		if (jwt == null || DoogiesUtil.isEmpty(jwt.getName())) return Optional.empty();
		String email = jwt.getName();   //TODO: or getClaim(Claims.sub) ?
		//TODO: this is performance critical.  Maybe cach currentUsers. At least for a minute. Or would this be a security issue?
		log.debug("Loading current user from DB ... {}", email);
		Optional<UserEntity> userOpt = UserEntity.findByEmail(email);
		if (userOpt.isEmpty()) {
			log.warn("Valid JWT, but user <{}> not found in DB!", email);
			return userOpt;
		}
		this.currentUser = userOpt.get();
		return userOpt;
	}

	/**
	 * Manually set the currently logged-in user and team.
	 * @param user a UserEntity
	 * @param team the team the user shall be logged into
	 */
	public void setCurrentUserAndTeam(UserEntity user, TeamEntity team) {
		if (user == null) throw new RuntimeException("Cannot login NULL");
		this.currentUser = user;
		this.currentTeam = team;
	}

	public boolean isAdmin() {
		return (jwt != null && jwt.getGroups().contains(LIQUIDO_ADMIN_ROLE));
	}

	// I could also augment the Quarkus SecurityIdentity:  https://quarkus.io/guides/security-customization#security-identity-customization
	// and add my LIQUIDO UserEntity as attribute

	/**
	 * Get the team that the user is currently logged into.
	 * @return the user's current team
	 */
	public Optional<TeamEntity> getCurrentTeam() {
		if (this.currentTeam != null) return Optional.of(currentTeam);
		Optional<UserEntity> userOpt = getCurrentUser();
		if (userOpt.isEmpty()) return Optional.empty();
		Long teamId = Long.valueOf(jwt.getClaim(JwtTokenUtils.TEAM_ID_CLAIM));
		Optional<TeamEntity> teamOpt = TeamEntity.findByIdOptional(teamId);
		if (teamOpt.isEmpty()) {
			log.warn("Valid JWT for " + userOpt.get().toStringShort() + ", but not logged into any team. This should not happen.");
			return teamOpt;
		}
		this.currentTeam = teamOpt.get();
		return teamOpt;
	}


}