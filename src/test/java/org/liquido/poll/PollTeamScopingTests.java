package org.liquido.poll;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.hamcrest.core.DescribedAs;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.TestFixtures;
import org.liquido.team.TeamDataResponse;
import org.liquido.util.Lson;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for A2 (team-scoping of poll operations): before the fix, seven endpoints in
 * PollsGraphQL did a bare {@code PollEntity.findByIdOptional(pollId)} with no ownership check, so
 * any authenticated user -- even the admin of an unrelated, throwaway team -- could look up,
 * modify, or finish another team's poll. Each of these endpoints must now report "not found"
 * (CANNOT_FIND_ENTITY) when called by a user whose current team does not own the poll.
 */
@QuarkusTest
public class PollTeamScopingTests {

	@Inject
	LiquidoTestUtils util;

	/** Send a GraphQL request expecting exactly one "not found" (CANNOT_FIND_ENTITY) error, not data. */
	private void assertNotFound(String query, Lson vars, String jwt, String opName) {
		ValidatableResponse res = given()
				.contentType(ContentType.JSON)
				.header("Authorization", "Bearer " + jwt)
				.body(String.format("{ \"query\": \"%s\", \"variables\": %s }", query, vars))
				.when()
				.post(TestFixtures.GRAPHQL_URI)
				.then()
				.statusCode(200);
		res.body("errors[0].extensions.liquidoException.liquidoErrorName", DescribedAs.describedAs(
				"'" + opName + "' must report not-found for a poll outside the caller's team, not leak its data",
				is("CANNOT_FIND_ENTITY")));
	}

	@Test
	public void pollOperations_areScopedToCallersTeam() {
		// GIVEN team A with a poll, and a completely unrelated team B
		TeamDataResponse teamA = util.createFreshTeam("ScopeTeamA");
		PollEntity pollA = util.createPoll("Team A's private poll", teamA.jwt);
		Long pollId = pollA.getId();

		TeamDataResponse teamB = util.createFreshTeam("ScopeTeamB");
		String jwtB = teamB.jwt;

		// WHEN team B's admin operates on team A's poll via each of the seven affected endpoints
		// THEN every single one reports "not found" -- never team A's actual poll data

		assertNotFound(
				"query poll($pollId: BigInteger!) { poll(pollId: $pollId) { id } }",
				new Lson("pollId", pollId), jwtB, "poll");

		assertNotFound(
				"mutation addProposal($pollId: BigInteger!, $title: String!, $description: String!, $icon: String!) { " +
						"addProposal(pollId: $pollId, title: $title, description: $description, icon: $icon) { id } }",
				Lson.builder().put("pollId", pollId).put("title", "x").put("description", "y").put("icon", "z"),
				jwtB, "addProposal");

		assertNotFound(
				"mutation likeProposal($pollId: BigInteger!, $proposalId: BigInteger!) { likeProposal(pollId: $pollId, proposalId: $proposalId) { id } }",
				Lson.builder().put("pollId", pollId).put("proposalId", 999999L), jwtB, "likeProposal");

		assertNotFound(
				"mutation startVotingPhase($pollId: BigInteger!) { startVotingPhase(pollId: $pollId) { id } }",
				new Lson("pollId", pollId), jwtB, "startVotingPhase");

		assertNotFound(
				"query voterToken($pollId: BigInteger!) { voterToken(pollId: $pollId) }",
				new Lson("pollId", pollId), jwtB, "voterToken");

		assertNotFound(
				"mutation finishVotingPhase($pollId: BigInteger!) { finishVotingPhase(pollId: $pollId) { id } }",
				new Lson("pollId", pollId), jwtB, "finishVotingPhase");

		assertNotFound(
				"query myBallot($pollId: BigInteger!) { myBallot(pollId: $pollId) { level } }",
				new Lson("pollId", pollId), jwtB, "getBallotOfCurrentUser (myBallot)");

		// Sanity check: team A's own admin CAN still access its own poll (the fix didn't overcorrect)
		ValidatableResponse ownTeamRes = TestFixtures.sendGraphQL(
				"query poll($pollId: BigInteger!) { poll(pollId: $pollId) { id title } }",
				new Lson("pollId", pollId), teamA.jwt);
		assertEquals(pollId.intValue(), ownTeamRes.extract().jsonPath().getInt("data.poll.id"));
	}
}
