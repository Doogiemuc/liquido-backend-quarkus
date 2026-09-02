package org.liquido.vote;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.hamcrest.core.DescribedAs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.TestFixtures;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.Lson;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>A voter holds at most ONE live voting token per poll</h1>
 *
 * {@code voterToken(pollId)} can be called any number of times. Each call used to mint another
 * 20-minute {@link OneTimeVotingToken} without invalidating the previous one, so a voter could
 * accumulate unboundedly many live tokens for a single poll. That is two problems at once:
 * an unbounded write primitive for any authenticated user, and the fuel for the double-vote race
 * (two valid tokens, two concurrent castVote calls, two ballots for one voter).
 *
 * <p>Issuing a token now revokes whatever that voter still held for that poll. It has to
 * <b>revoke</b> rather than re-issue, because only the hash of a token is stored - the plain token
 * went to the voter and was kept nowhere, so it cannot be handed out twice.
 *
 * <p>This narrows the race window; it does not close it. The database constraint does that
 * (see the {@code uq_ballot_poll_voter} work). Both are needed.
 *
 * <p>Uses its own throwaway team, per the seed contract: it casts a real vote over HTTP, and
 * {@code @TestTransaction} would not roll that back.
 */
@QuarkusTest
@DisplayName("One-time voting token issuance is bounded")
public class OneTimeVoterTokenIssuanceTest {

	@Inject
	LiquidoTestUtils util;

	@Inject
	LiquidoConfig config;

	@Test
	@DisplayName("Asking for a second token revokes the first, leaving exactly one live token")
	public void issuingATokenRevokesTheVotersPreviousOne() {
		// GIVEN a poll in VOTING in a team of this test's own
		TeamDataResponse team = util.createFreshTeam("TokenIssuance");
		PollEntity poll = util.createPoll("Poll for token issuance test", team.jwt);
		poll = util.addProposal(poll.getId(), "Token test option A",
				"The first alternative, written by the admin of this throwaway team.", "hand-peace", team.jwt);
		poll = util.addProposal(poll.getId(), "Token test option B",
				"The second alternative, written by the admin of this throwaway team.", "hand-rock", team.jwt);
		poll = util.startVotingPhase(poll.getId(), team.jwt);
		Long pollId = poll.getId();
		List<Long> voteOrder = poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList();

		// WHEN the same voter asks for a voter token twice for the SAME poll
		String firstToken = util.getVoterToken(pollId, team.jwt);
		String secondToken = util.getVoterToken(pollId, team.jwt);
		assertNotEquals(firstToken, secondToken, "each call must mint a fresh random token");

		// THEN only one live token remains in the database for this voter and poll
		assertEquals(1, liveTokenCount(team.user, pollId),
				"A voter must hold at most ONE live token per poll. Issuing a token has to revoke the " +
				"previous one, or a voter can stockpile tokens and race them against each other.");

		// ... the revoked first token is worthless
		assertCastVoteRejected(pollId, voteOrder, firstToken);

		// ... and the token they were last given still works
		assertNotNull(util.castVote(pollId, voteOrder, secondToken).getBallot().getChecksum(),
				"the most recently issued token must still be able to cast a vote");

		// ... and consuming it leaves nothing behind
		assertEquals(0, liveTokenCount(team.user, pollId),
				"a consumed token must be deleted, so no live token is left for this voter and poll");
	}

	/** How many one-time tokens this voter currently holds for this poll. */
	private long liveTokenCount(UserEntity voter, Long pollId) {
		return QuarkusTransaction.requiringNew().call(() -> {
			RightToVoteEntity rightToVote = RightToVoteEntity.findByVoter(voter, config.hashSecret())
					.orElseThrow(() -> new AssertionError("test voter has no RightToVote"));
			PollEntity poll = PollEntity.findById(pollId);
			return OneTimeVotingToken.count("rightToVote = ?1 and poll = ?2", rightToVote, poll);
		});
	}

	/** Casting a vote with this token must be refused as an invalid voter token, not accepted. */
	private void assertCastVoteRejected(Long pollId, List<Long> voteOrder, String voterToken) {
		String query = "mutation castVote($pollId: BigInteger!, $voteOrderIds: [BigInteger!]!, $voterToken: String!) { " +
				"  castVote(pollId: $pollId, voteOrderIds: $voteOrderIds, voterToken: $voterToken) " +
				"  { voteCount ballot { id checksum } } }";
		Lson vars = Lson.builder()
				.put("pollId", pollId)
				.put("voteOrderIds", voteOrder)
				.put("voterToken", voterToken);

		ValidatableResponse res = given()
				.contentType(ContentType.JSON)
				.body(String.format("{ \"query\": \"%s\", \"variables\": %s }", query, vars))
				.when()
				.post(TestFixtures.GRAPHQL_URI)
				.then()
				.statusCode(200);

		res.body("errors[0].extensions.liquidoException.liquidoErrorName", DescribedAs.describedAs(
				"A token that was superseded by a newer one must no longer be able to cast a vote",
				is("INVALID_VOTER_TOKEN")));
	}
}
