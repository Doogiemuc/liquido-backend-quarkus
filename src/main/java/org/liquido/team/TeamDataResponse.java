package org.liquido.team;

import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.liquido.user.UserEntity;

/**
 * GraphQL response for createNewTeam, joinTeam and login mutation.
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
}