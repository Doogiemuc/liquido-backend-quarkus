package org.liquido.vote;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;

import java.util.List;

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
		PersistenceException thrown = assertThrows(PersistenceException.class, () ->
			QuarkusTransaction.requiringNew().run(() -> {
				PollEntity poll = PollEntity.findById(voted.pollId());
				RightToVoteEntity rightToVote = RightToVoteEntity
						.findByVoter(voted.voter(), config.hashSecret())
						.orElseThrow(() -> new AssertionError("test voter has no RightToVote"));
				List<ProposalEntity> voteOrder = voted.voteOrder().stream()
						.map(id -> (ProposalEntity) ProposalEntity.findById(id)).toList();

				new BallotEntity(poll, 0, voteOrder, rightToVote).persist();
				BallotEntity.flush();
			}),
			"uq_ballot_poll_voter must refuse a second ballot for one voter in one poll. Without it, " +
			"two concurrent castVote calls both insert and calcWinnerOfPoll() counts both."
		);
		assertNotNull(thrown);

		assertEquals(1, ballotCount(voted.pollId()), "the poll must still hold exactly one ballot");
	}

	@Test
	@DisplayName("A voter may still change their vote while the poll is open")
	public void reVotingStillUpdatesTheSameBallot() {
		VotedPoll voted = aPollWithOneCastVote("BallotReVote");

		// Casting again with a freshly issued token must UPDATE the existing ballot, not insert a
		// second one. The constraint must not break the documented "you may change your vote" path.
		List<Long> reversed = voted.voteOrder().reversed();
		util.castVote(voted.pollId(), reversed, util.getVoterToken(voted.pollId(), tokenJwtFor(voted)));

		assertEquals(1, ballotCount(voted.pollId()),
				"re-voting must update the voter's ballot, never add a second one");
	}

	/**
	 * The admin of the throwaway team is the only voter, and devLogin pins them to their one team.
	 * (createFreshTeam's admin belongs to exactly one team, so this is unambiguous.)
	 */
	private String tokenJwtFor(VotedPoll voted) {
		return util.devLogin(voted.voter().email).jwt;
	}

	private long ballotCount(Long pollId) {
		return QuarkusTransaction.requiringNew().call(() ->
				BallotEntity.count("poll", (PollEntity) PollEntity.findById(pollId)));
	}
}
