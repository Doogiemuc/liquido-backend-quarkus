package org.liquido.team;

import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.liquido.user.UserEntity;

import java.util.List;

/**
 * Response DTO for createNewTeam, joinTeam and logins.
 */
@NoArgsConstructor
@RequiredArgsConstructor
public class TeamDataResponse {

	@lombok.NonNull
	public TeamEntity team;

	@lombok.NonNull
	public UserEntity user;

	@lombok.NonNull
	public String jwt;

	/**
	 * All teams this user is a member of, so that the client can offer a team switcher.
	 * Always populated by {@code JwtTokenUtils.doLoginInternal()}, and therefore by every login path.
	 * Contains at least {@link #team} itself.
	 *
	 * <p>This is the <b>only</b> place a user learns which teams exist for an email, and it is
	 * reachable only with a valid authentication. Team names must never be exposed by an
	 * unauthenticated endpoint such as {@code /login/check-login-email}: emails are guessable, so
	 * that would make team membership enumerable for anyone.
	 *
	 * <p>Deliberately no {@code @lombok.NonNull} - that would pull the field into
	 * {@code @RequiredArgsConstructor} and break every existing {@code new TeamDataResponse(team, user, jwt)}.
	 */
	public List<TeamSummary> teams = List.of();

	/**
	 * A short abbreviated string representation of a TeamDataResponse
	 * suitable for logging
	 * @return String representation of a TeamDataResponse
	 */
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append("TeamDataResponse[");
 		buf.append("team.teamName=").append(team.teamName).append(", ");
		buf.append(user.toStringShort()).append(", ");
		buf.append("jwt=").append(jwt, 0, 10).append("...]");
		return buf.toString();
	}
}