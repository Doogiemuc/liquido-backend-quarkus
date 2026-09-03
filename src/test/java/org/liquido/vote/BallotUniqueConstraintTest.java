package org.liquido.vote;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import org.hamcrest.core.DescribedAs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.TestFixtures;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.Lson;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>One ballot per voter per poll, enforced by the database</h1>
 *
 * {@code CastVoteService.castVoteRec()} does read-then-insert: it looks for an existing ballot and
 * then either updates it or inserts a new one. Between that read and that insert there is a window,
 * and two concurrent casts for the same voter both saw "no ballot" and both inserted. The winner
 * calculation then counted both, because {@code calcWinnerOfPoll()} lists every ballot in the poll.
 *
 * <p>{@code uq_ballot_poll_voter} closes that window. The application check stays, but only to
 * produce a readable error - the constraint is the authority. This is the same decision
 * {@code PollyBallotEntity} already took with {@code uq_polly_ballot_voter}.
 *
 * <h2>Why this does not simply race two HTTP calls</h2>
 *
 * The obvious test - issue two voter tokens, fire two concurrent castVote calls - is no longer
 * reachable through the API, because issuing a token now revokes the voter's previous one
 * ({@link OneTimeVoterTokenIssuanceTest}), so a voter can never hold two usable tokens at once.
 * The two fixes compose: one narrows the window, the other closes it. What is left to verify is
 * that the constraint really exists in the schema and really refuses the second row - which is what
 * a raced insert would ultimately hit - and that it does not break the legitimate paths.
 */
@QuarkusTest
@DisplayName("A poll cannot hold two ballots for one voter")
public class BallotUniqueConstraintTest {

	@Inject
	LiquidoTestUtils util;

	@Inject
	LiquidoConfig config;

	/** A fresh team with a poll in VOTING, and one vote already cast by the team's admin. */
	private record VotedPoll(Long pollId, UserEntity voter, List<Long> voteOrder) {}

	private VotedPoll aPollWithOneCastVote(String prefix) {
		TeamDataResponse team = util.createFreshTeam(prefix);
		PollEntity poll = util.createPoll("Poll for " + prefix, team.jwt);
		poll = util.addProposal(poll.getId(), prefix + " option A",
				"The first alternative in this throwaway team's poll.", "hand-peace", team.jwt);
		poll = util.addProposal(poll.getId(), prefix + " option B",
				"The second alternative in this throwaway team's poll.", "hand-rock", team.jwt);
		poll = util.startVotingPhase(poll.getId(), team.jwt);

		List<Long> voteOrder = poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		util.castVote(poll.getId(), voteOrder, util.getVoterToken(poll.getId(), team.jwt));
		return new VotedPoll(poll.getId(), team.user, voteOrder);
	}

	@Test
	@DisplayName("The database refuses a second ballot for the same voter in the same poll")
	public void secondBallotForTheSameVoterIsRejected() {
		VotedPoll voted = aPollWithOneCastVote("BallotConstraint");

		// Insert a second ballot for the SAME poll and the SAME RightToVote, straight through JPA -
		// exactly the row a lost read-then-insert race would produce.
		// Derived in its own transaction first, so the insert below is the only thing under assertThrows.
		String pseudonym = QuarkusTransaction.requiringNew().call(() -> {
			PollEntity poll = PollEntity.findById(voted.pollId());
			return RightToVoteEntity.findByVoterAndTeam(voted.voter(), poll.getTeam(), config)
					.orElseThrow(() -> new AssertionError("test voter has no RightToVote in this team"))
					.deriveBallotPseudonym(poll.id, config);
		});

		PersistenceException thrown = assertThrows(PersistenceException.class, () ->
			QuarkusTransaction.requiringNew().run(() -> {
				PollEntity poll = PollEntity.findById(voted.pollId());
				List<ProposalEntity> voteOrder = voted.voteOrder().stream()
						.map(id -> (ProposalEntity) ProposalEntity.findById(id)).toList();

				// Same poll-scoped pseudonym the real cast derived, so this is the exact duplicate row.
				new BallotEntity(poll, 0, voteOrder, pseudonym).persist();
				BallotEntity.flush();
			}),
			"uq_ballot_poll_voter must refuse a second ballot for one voter in one poll. Without it, " +
			"two concurrent castVote calls both insert and calcWinnerOfPoll() counts both."
		);
		assertNotNull(thrown);

		assertEquals(1, ballotCount(voted.pollId()), "the poll must still hold exactly one ballot");
	}

	@Test
	@DisplayName("A direct vote, once cast, cannot be changed")
	public void reVotingIsRejected() {
		VotedPoll voted = aPollWithOneCastVote("BallotReVote");

		// A second direct cast with a freshly issued token (a fresh token, because P0-3 already
		// revoked the first one) must be REJECTED with ALREADY_VOTED, not silently accepted as an
		// update. See CastVoteService.castVoteRec(): the reject is scoped to level 0 on both sides,
		// so it fires here without touching the separate "voter's own vote overrides a proxy" path.
		List<Long> reversed = voted.voteOrder().reversed();
		assertCastVoteRejectedAsAlreadyVoted(voted.pollId(), reversed, util.getVoterToken(voted.pollId(), tokenJwtFor(voted)));

		assertEquals(1, ballotCount(voted.pollId()),
				"a rejected re-vote must not add a second ballot");
		assertEquals(voted.voteOrder(), storedVoteOrder(voted.pollId()),
				"a rejected re-vote must not change the originally cast ballot either");
	}

	/**
	 * The admin of the throwaway team is the only voter, and devLogin pins them to their one team.
	 * (createFreshTeam's admin belongs to exactly one team, so this is unambiguous.)
	 */
	private String tokenJwtFor(VotedPoll voted) {
		return util.devLogin(voted.voter().email).jwt;
	}

	/** Casting a vote with this token must be refused as ALREADY_VOTED, not accepted. */
	private void assertCastVoteRejectedAsAlreadyVoted(Long pollId, List<Long> voteOrder, String voterToken) {
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
				"A second direct cast for the same voter and poll must be rejected as ALREADY_VOTED",
				is("ALREADY_VOTED")));
	}

	private List<Long> storedVoteOrder(Long pollId) {
		return QuarkusTransaction.requiringNew().call(() -> {
			PollEntity poll = PollEntity.findById(pollId);
			return BallotEntity.<BallotEntity>find("poll", poll).firstResult()
					.getVoteOrder().stream().map(LiquidoBaseEntity::getId).toList();
		});
	}

	private long ballotCount(Long pollId) {
		return QuarkusTransaction.requiringNew().call(() ->
				BallotEntity.count("poll", (PollEntity) PollEntity.findById(pollId)));
	}
}
