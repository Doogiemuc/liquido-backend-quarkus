package org.liquido.graphql;

import lombok.RequiredArgsConstructor;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;

/**
 * GraphQL response for createNewTeam, joinTeam and login mutation.
 */
@RequiredArgsConstructor
public class TeamDataResponse {

	@lombok.NonNull
	public TeamEntity team;

	@lombok.NonNull
	public UserEntity user;

	@lombok.NonNull
	public String jwt;
}
