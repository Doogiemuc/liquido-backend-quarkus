package org.liquido.poll;

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
import org.liquido.team.TeamDataResponse;
import org.liquido.util.Lson;
import org.liquido.vote.Matrix;
import org.liquido.vote.RankedPairVoting;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>P3-1: the announced winner can be checked, not merely trusted</h1>
 *
 * A voter could already confirm their own ballot by its checksum. That is individual verifiability,
 * and on its own it says nothing about the announced result -- a server that counted honestly and a
 * server that discarded half the ballots look identical to someone who can only see their own.
 *
 * <p>The decisive test is therefore not "does the endpoint return data" but <b>can a third party
 * reach the server's answer without trusting the server</b>. So this test takes the published
 * ballots, recomputes the duel matrix and re-runs Ranked Pairs itself, and compares. If the
 * published data were incomplete or its axes were mislabelled, the recomputation would diverge --
 * which is exactly the failure a verifiable tally is supposed to expose.
 */
@QuarkusTest
@DisplayName("A finished poll's tally can be recomputed independently")
public class PublishedTallyTest {

	@Inject
	LiquidoTestUtils util;

	@Test
	@DisplayName("Recomputing Ranked Pairs from the published ballots reproduces the announced winner")
	public void publishedTallyReproducesTheAnnouncedWinner() {
		// GIVEN a finished poll that several different voters actually voted in
		TeamDataResponse team = util.createFreshTeam("PublishedTally");
		PollEntity poll = util.createPoll("Poll to publish a tally for", team.jwt);
		poll = util.addProposal(poll.getId(), "Tally option A", "The first alternative in this poll, described at sufficient length.", "hand-peace", team.jwt);
		poll = util.addProposal(poll.getId(), "Tally option B", "The second alternative in this poll, described at sufficient length.", "hand-rock", team.jwt);
		poll = util.addProposal(poll.getId(), "Tally option C", "The third alternative in this poll, described at sufficient length.", "hand-scissors", team.jwt);
		poll = util.startVotingPhase(poll.getId(), team.jwt);
		Long pollId = poll.getId();

		List<Long> ids = poll.getProposals().stream().map(LiquidoBaseEntity::getId).sorted().toList();

		// Three voters with three different rankings, so the matrix is not trivially symmetric
		castAs(team, pollId, List.of(ids.get(0), ids.get(1), ids.get(2)));
		String memberJwt = util.joinTeam(team.team.getInviteCode(), null).jwt;
		util.castVote(pollId, List.of(ids.get(1), ids.get(2), ids.get(0)), util.getVoterToken(pollId, memberJwt));
		String thirdJwt = util.joinTeam(team.team.getInviteCode(), null).jwt;
		util.castVote(pollId, List.of(ids.get(0), ids.get(2), ids.get(1)), util.getVoterToken(pollId, thirdJwt));

		util.finishVotingPhase(pollId, team.jwt);

		// WHEN we read the published tally
		ValidatableResponse res = requestTally(pollId, team.jwt);
		String path = "data.publishedTally.";
		// JSON numbers arrive as Integer, so every id is widened explicitly rather than cast.
		List<Long> proposalOrder = toLongs(res.extract().jsonPath().getList(path + "proposalOrder"));
		Long announcedWinner = ((Number) res.extract().jsonPath().get(path + "winnerId")).longValue();
		int numBallots = res.extract().jsonPath().getInt(path + "numBallots");
		List<List<Long>> publishedVoteOrders = res.extract().jsonPath().<List<Object>>getList(path + "ballots.voteOrder")
				.stream().map(this::toLongs).toList();
		List<String> checksums = res.extract().jsonPath().getList(path + "ballots.checksum", String.class);

		assertEquals(3, numBallots, "all three ballots must be published");
		assertEquals(3, publishedVoteOrders.size(), "every counted ballot must appear in the tally");
		assertEquals(ids, proposalOrder, "the published axes must be the proposal ids, ascending");
		assertTrue(checksums.stream().noneMatch(c -> c == null || c.isBlank()),
				"every published ballot must carry the checksum its voter can recognise");

		// THEN recomputing the whole thing from the published data alone reaches the same winner.
		// This is the actual claim: nothing here reads the database or trusts the server's matrix.
		Matrix recomputed = RankedPairVoting.calcDuelMatrix(proposalOrder, publishedVoteOrders);
		List<Integer> winnerIndexes = RankedPairVoting.calcRankedPairWinners(recomputed);

		// The matrix the server PUBLISHED must be the one the published ballots produce. Comparing only
		// the winners would miss a server whose matrix is indexed by an ordering other than the axes it
		// published -- the two can still agree on a winner by luck, and then the published matrix is a
		// grid of numbers that means something different from what its labels claim.
		List<List<Long>> announcedMatrix = readMatrix(res, path + "duelMatrix");
		assertEquals(asNestedList(recomputed), announcedMatrix,
				"The duel matrix the server published must equal the one recomputed from the published " +
				"ballots, read along the published axes. A mismatch means the matrix and its stated axes " +
				"disagree, so nobody can check the count against it.");

		assertFalse(winnerIndexes.isEmpty(), "an independent recomputation must find a winner too");
		Long recomputedWinner = proposalOrder.get(winnerIndexes.get(0));
		assertEquals(announcedWinner, recomputedWinner,
				"The winner recomputed from the published ballots must match the one the server announced. " +
				"If these differ, either the published set is incomplete or the announced result does not " +
				"follow from it -- which is precisely what a verifiable tally exists to reveal.");
	}

	@Test
	@DisplayName("A poll still in voting publishes nothing, so the running tally cannot leak")
	public void noTallyWhileVotingIsOpen() {
		TeamDataResponse team = util.createFreshTeam("TallyTooEarly");
		PollEntity poll = util.createPoll("Poll whose tally must stay closed", team.jwt);
		poll = util.addProposal(poll.getId(), "Too early option A", "The first alternative, described at sufficient length.", "hand-peace", team.jwt);
		poll = util.addProposal(poll.getId(), "Too early option B", "The second alternative, described at sufficient length.", "hand-rock", team.jwt);
		poll = util.startVotingPhase(poll.getId(), team.jwt);
		util.castVote(poll.getId(), poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList(),
				util.getVoterToken(poll.getId(), team.jwt));

		// A poll deliberately has no link to its ballots so the running result cannot leak while people
		// are still voting. Publishing a tally early would hand back exactly what that protects.
		requestTally(poll.getId(), team.jwt)
				.body("errors[0].extensions.liquidoException.liquidoErrorName", DescribedAs.describedAs(
						"A tally must not be published while the poll is still open for voting",
						is("INVALID_POLL_STATUS")));
	}

	@Test
	@DisplayName("Another team's tally is not readable")
	public void anotherTeamsTallyIsNotReadable() {
		TeamDataResponse owner = util.createFreshTeam("TallyOwner");
		PollEntity poll = util.createPoll("Private poll of one team", owner.jwt);
		poll = util.addProposal(poll.getId(), "Private option A", "The first alternative, described at sufficient length.", "hand-peace", owner.jwt);
		poll = util.addProposal(poll.getId(), "Private option B", "The second alternative, described at sufficient length.", "hand-rock", owner.jwt);
		poll = util.startVotingPhase(poll.getId(), owner.jwt);
		util.finishVotingPhase(poll.getId(), owner.jwt);

		// The tally exposes every ballot's ranking. Universal verifiability means the ELECTORATE can
		// check the count -- for a team poll that is the team, not the whole internet.
		TeamDataResponse outsider = util.createFreshTeam("TallyOutsider");
		requestTally(poll.getId(), outsider.jwt)
				.body("errors[0].extensions.liquidoException.liquidoErrorName", DescribedAs.describedAs(
						"A poll outside the caller's team must report not-found, never its ballots",
						is("CANNOT_FIND_ENTITY")));
	}

	// ------------------------------------------------------------------ helpers

	private void castAs(TeamDataResponse team, Long pollId, List<Long> voteOrder) {
		util.castVote(pollId, voteOrder, util.getVoterToken(pollId, team.jwt));
	}

	/** Read the published duel matrix back as plain nested lists, without going through the server. */
	private List<List<Long>> readMatrix(ValidatableResponse res, String path) {
		return res.extract().jsonPath().<List<Object>>getList(path).stream().map(this::toLongs).toList();
	}

	/** The same shape, computed locally, so the two can be compared element by element. */
	private List<List<Long>> asNestedList(Matrix matrix) {
		return java.util.stream.IntStream.range(0, matrix.getRows())
				.mapToObj(i -> java.util.stream.LongStream.range(0, matrix.getCols())
						.mapToObj(j -> matrix.get(i, (int) j)).toList())
				.toList();
	}

	/** JSON numbers deserialize as Integer; ids in this codebase are Long. */
	private List<Long> toLongs(List<?> numbers) {
		return numbers.stream().map(n -> ((Number) n).longValue()).toList();
	}

	private ValidatableResponse requestTally(Long pollId, String jwt) {
		String query = "query publishedTally($pollId: BigInteger!) { publishedTally(pollId: $pollId) " +
				"{ pollId proposalOrder winnerId numBallots duelMatrix ballots { checksum voteOrder } } }";
		return given()
				.contentType(ContentType.JSON)
				.header("Authorization", "Bearer " + jwt)
				.body(String.format("{ \"query\": \"%s\", \"variables\": %s }", query, new Lson("pollId", pollId)))
				.when()
				.post(TestFixtures.GRAPHQL_URI)
				.then()
				.statusCode(200);
	}
}
