package org.liquido.team;

import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.liquido.user.UserEntity;

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