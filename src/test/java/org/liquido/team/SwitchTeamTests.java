package org.liquido.team;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.hamcrest.core.DescribedAs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.TestFixtures;
import org.liquido.util.Lson;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for switching between the teams of one user ({@code TeamGraphQL.switchTeam}).
 *
 * <h3>Why these build their own teams instead of using the seed</h3>
 *
 * {@code switchTeam} writes {@code lastTeamId}, and the seed contract (see AGENTS.md and
 * {@code SeedContractTests}) forbids changing that on seed rows: it alters other tests'
 * <i>preconditions</i> rather than adding to them, and {@code @TestTransaction} does not roll back an
 * HTTP-triggered mutation. So every scenario here stands up its own throwaway teams with
 * {@link LiquidoTestUtils#createFreshTeam(String)}. That also means these tests do not depend on the
 * seed having been regenerated with the multi-team scenario in it.
 */
@QuarkusTest
public class SwitchTeamTests {

	@Inject
	LiquidoTestUtils util;

	private static final String SWITCH_TEAM_QUERY =
			"mutation switchTeam($teamId: BigInteger!) { switchTeam(teamId: $teamId) " +
					"{ team { id teamName } user { id email } teams { id teamName } jwt } }";

	/** Send switchTeam and expect it to succeed. */
	private ValidatableResponse switchTeam(Long teamId, String jwt) {
		return given()
				.contentType(ContentType.JSON)
				.header("Authorization", "Bearer " + jwt)
				.body(String.format("{ \"query\": \"%s\", \"variables\": %s }", SWITCH_TEAM_QUERY, new Lson("teamId", teamId)))
				.when()
				.post(TestFixtures.GRAPHQL_URI)
				.then()
				.statusCode(200)
				.body("errors", anyOf(nullValue(), hasSize(0)));
	}

	/** Send switchTeam and expect exactly the named LiquidoException, and no data. */
	private void assertSwitchTeamFails(Long teamId, String jwt, String expectedErrorName, String because) {
		given()
				.contentType(ContentType.JSON)
				.header("Authorization", "Bearer " + jwt)
				.body(String.format("{ \"query\": \"%s\", \"variables\": %s }", SWITCH_TEAM_QUERY, new Lson("teamId", teamId)))
				.when()
				.post(TestFixtures.GRAPHQL_URI)
				.then()
				.statusCode(200)
				.body("errors[0].extensions.liquidoException.liquidoErrorName",
						DescribedAs.describedAs(because, is(expectedErrorName)))
				.body("data.switchTeam", nullValue());
	}

	/**
	 * Build a user who is a member of two teams: admin of their own team A, and a joined member of
	 * someone else's team B. Returns [sessionInA, sessionInB] - note each response's jwt is scoped to
	 * its own team.
	 */
	private TeamDataResponse[] userInTwoTeams(String prefix) {
		TeamDataResponse inTeamA = util.createFreshTeam(prefix + "A");
		TeamDataResponse foreignTeamB = util.createFreshTeam(prefix + "B");
		TeamDataResponse inTeamB = util.joinTeamAsRegisteredUser(
				foreignTeamB.team.inviteCode, inTeamA.user, inTeamA.jwt);
		assertEquals(inTeamA.user.id, inTeamB.user.id, "Must be the very same user in both teams");
		assertNotEquals(inTeamA.team.id, inTeamB.team.id, "Must really be two different teams");
		return new TeamDataResponse[]{inTeamA, inTeamB};
	}

	@Test
	@DisplayName("A member of two teams can switch between them, and the new JWT is scoped to the new team")
	public void memberOfTwoTeamsCanSwitch() {
		// GIVEN a user in team A and team B, whose current session is scoped to B (they joined it last)
		TeamDataResponse[] sessions = userInTwoTeams("SwitchOk");
		TeamDataResponse inTeamA = sessions[0], inTeamB = sessions[1];

		// WHEN they switch back into team A
		ValidatableResponse res = switchTeam(inTeamA.team.id, inTeamB.jwt)
				.body("data.switchTeam.team.id", is(inTeamA.team.id.intValue()))
				.body("data.switchTeam.user.id", is(inTeamA.user.id.intValue()));
		String newJwt = res.extract().jsonPath().getString("data.switchTeam.jwt");
		assertNotNull(newJwt, "switchTeam must hand back a new JWT");

		// THEN the new JWT really is scoped to team A -- the team claim is what every team-scoped
		// lookup compares against, so this is the assertion that actually matters.
		TeamEntity ownTeam = util.loadOwnTeam(newJwt);
		assertEquals(inTeamA.team.id, ownTeam.id,
				"After switching, `query { team }` with the new JWT must answer with the new team");
	}

	@Test
	@DisplayName("Switching persists, so the next login lands in the newly chosen team")
	public void switchingSetsTheLastTeam() {
		// GIVEN a user whose session is in team B
		TeamDataResponse[] sessions = userInTwoTeams("SwitchLast");
		TeamDataResponse inTeamA = sessions[0], inTeamB = sessions[1];

		// WHEN they switch into team A
		switchTeam(inTeamA.team.id, inTeamB.jwt);

		// THEN a fresh login that does NOT pin a team lands in A, not in the team they joined last.
		// This is the whole point of writing lastTeamId: the choice survives closing the app.
		TeamDataResponse relogin = util.devLogin(inTeamA.user.email);
		assertEquals(inTeamA.team.id, relogin.team.id,
				"A login without an explicit team must use the team the user last switched into");
	}

	@Test
	@DisplayName("Every login response lists all of the user's teams, and only theirs")
	public void loginResponseListsTheUsersTeams() {
		TeamDataResponse[] sessions = userInTwoTeams("SwitchTeamList");
		TeamDataResponse inTeamA = sessions[0], inTeamB = sessions[1];

		// The multi-team user sees exactly their own two teams ...
		switchTeam(inTeamA.team.id, inTeamB.jwt)
				.body("data.switchTeam.teams", hasSize(2))
				.body("data.switchTeam.teams.id", containsInAnyOrder(
						inTeamA.team.id.intValue(), inTeamB.team.id.intValue()));

		// ... and a user who belongs to one team sees exactly one. That is what keeps the frontend's
		// switcher hidden for the overwhelmingly common single-team user (progressive disclosure).
		TeamDataResponse soloTeam = util.createFreshTeam("SwitchSolo");
		assertEquals(1, soloTeam.teams.size(),
				"A user of a single team must get exactly that one team in their login response");
		assertEquals(soloTeam.team.id, soloTeam.teams.get(0).id(),
				"...and it must be the team they are logged into");
	}

	@Test
	@DisplayName("A user cannot switch into a team they are not a member of")
	public void cannotSwitchIntoAForeignTeam() {
		TeamDataResponse me = util.createFreshTeam("SwitchIntruder");
		TeamDataResponse strangersTeam = util.createFreshTeam("SwitchVictim");

		assertSwitchTeamFails(strangersTeam.team.id, me.jwt,
				"CANNOT_LOGIN_USER_NOT_MEMBER_OF_TEAM",
				"switchTeam must refuse a team the caller is not a member of, however valid their JWT is");
	}

	@Test
	@DisplayName("Switching into a team that does not exist is rejected")
	public void cannotSwitchIntoAnUnknownTeam() {
		TeamDataResponse me = util.createFreshTeam("SwitchUnknown");

		assertSwitchTeamFails(-4711L, me.jwt,
				"CANNOT_LOGIN_TEAM_NOT_FOUND",
				"switchTeam must report a missing team as CANNOT_LOGIN_TEAM_NOT_FOUND");
	}

	@Test
	@DisplayName("An anonymous caller cannot switch teams at all")
	public void anonymousCallerCannotSwitchTeam() {
		TeamDataResponse someTeam = util.createFreshTeam("SwitchAnon");

		// No Authorization header at all. @RolesAllowed(LIQUIDO_USER_ROLE) is what stops this, so the
		// exact shape of the rejection is Quarkus's to choose - what must hold is that no team data
		// and no JWT come back.
		given()
				.contentType(ContentType.JSON)
				.body(String.format("{ \"query\": \"%s\", \"variables\": %s }",
						SWITCH_TEAM_QUERY, new Lson("teamId", someTeam.team.id)))
				.when()
				.post(TestFixtures.GRAPHQL_URI)
				.then()
				.statusCode(anyOf(is(200), is(401)))
				.body("data.switchTeam", nullValue());
	}
}
