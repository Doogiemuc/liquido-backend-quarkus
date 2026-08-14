package org.liquido.team;

/**
 * Minimal information about a team, just enough for the client to offer a team switcher:
 * a name to show and an id to switch to.
 *
 * <p>Deliberately <b>not</b> a {@link TeamEntity}. That entity eagerly fetches both its
 * {@code members} and its {@code polls}, so returning a list of them - which is what a login
 * response for a multi-team user would do - would drag every poll and every proposal of every
 * one of the user's teams into the payload.
 *
 * @param id the team's database id, which is what {@code TeamGraphQL.switchTeam} takes
 * @param teamName the team's name, unique over all teams
 */
public record TeamSummary(Long id, String teamName) {

	/** Project a TeamEntity down to the little that a team switcher needs. */
	public static TeamSummary of(TeamEntity team) {
		return new TeamSummary(team.id, team.teamName);
	}
}
