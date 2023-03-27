package org.liquido.graphql;

import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;

/**
 * GraphQL response for createNewTeam, joinTeam and login mutation.
 */
public class TeamDataResponse {
	// We make it easy and use public fields.
	public TeamEntity team;

	public UserEntity user;

	public String jwt;
}
