package org.liquido.util;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
import org.liquido.vote.BallotEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>The purge actually deletes, and survives the schema's two nasty shapes</h1>
 *
 * {@link TestDataPurger} replaced a helper that had <b>never executed</b> - it was guarded by
 * {@code LaunchMode != DEVELOPMENT}, which is always true under {@code @QuarkusTest}, so it silently
 * returned having deleted nothing and no caller could tell. Everything it does is therefore treated
 * as unverified until a test like this one runs it.
 *
 * <p>The case that matters is a <b>FINISHED</b> poll, because that is the only state in which
 * {@code polls.winner_id} is set, and that column closes a foreign key cycle with
 * {@code proposals.poll_id}. Deleting the proposals first leaves the poll pointing at a deleted row;
 * deleting the poll first leaves the proposals orphaned. A finished poll also has ballots, whose
 * {@code ballot_voteorder} join rows a bulk delete would leave behind to block the proposal delete.
 */
@QuarkusTest
@DisplayName("TestDataPurger removes a team with a FINISHED poll, ballots and all")
public class TestDataPurgerTest {

	@Inject
	LiquidoTestUtils util;

	@Inject
	TestDataPurger purger;

	@Test
	@DisplayName("A team with a finished poll, a winner and cast ballots can be purged completely")
	public void purgesATeamWithAFinishedPoll() {
		// GIVEN a throwaway team that has run a poll all the way to a winner
		TeamDataResponse team = util.createFreshTeam("PurgeFinished");
		String teamName = team.team.getTeamName();
		Long teamId = team.team.id;

		long unique = System.currentTimeMillis();
		PollEntity poll = util.createPoll("Purge me " + unique, team.jwt);
		Long pollId = poll.getId();
		String desc = "Long enough to clear the backend's @Size(min=20) check. " + unique;
		util.addProposal(pollId, "Purge option A " + unique, desc, "bolt", team.jwt);
		poll = util.addProposal(pollId, "Purge option B " + unique, desc, "bolt", team.jwt);
		util.startVotingPhase(pollId, team.jwt);

		List<Long> voteOrder = poll.getProposals().stream().map(ProposalEntity::getId).toList();
		String voterToken = util.getVoterToken(pollId, team.jwt);
		util.castVote(pollId, voteOrder, voterToken);
		util.finishVotingPhase(pollId, team.jwt);

		// ... so the poll really does point at a winner. Without this the test would pass while
		// proving nothing about the foreign key cycle it exists to cover.
		PollEntity finished = PollEntity.findById(pollId);
		assertNotNull(finished.getWinner(), "the poll must have a winner, or this test covers nothing");
		assertFalse(BallotEntity.<BallotEntity>list("poll", finished).isEmpty(), "the poll must have ballots");

		// WHEN the team is purged
		TestDataPurger.PurgeReport report = purger.purgeTeam(teamName, true);

		// THEN everything is gone - no foreign key violation, and nothing left orphaned
		assertEquals(1, report.teams, "one team purged");
		assertTrue(report.polls >= 1, "the poll must be counted as purged");
		assertTrue(report.ballots >= 1, "the ballot must be counted as purged");

		// Asserted with count queries, not findById: the purge ran in this same persistence context, so
		// findById would hand back the still-cached instance and pass or fail for the wrong reason.
		assertEquals(0, TeamEntity.count("id", teamId), "team must be deleted");
		assertEquals(0, PollEntity.count("id", pollId), "poll must be deleted");
		assertEquals(0, BallotEntity.count("poll.id", pollId), "ballots must be deleted");
		assertEquals(0, ProposalEntity.count("poll.id", pollId), "proposals must be deleted");
	}

	@Test
	@DisplayName("Purging a team that does not exist is a no-op, not an error")
	public void purgingAnAbsentTeamIsANoOp() {
		// A first run against a clean database must not blow up. The previous implementation threw
		// IllegalArgumentException here, which would have made purge-then-recreate impossible.
		TestDataPurger.PurgeReport report = purger.purgeTeam("NoSuchTeam" + System.currentTimeMillis(), true);
		assertEquals(0, report.teams);
	}

	@Test
	@DisplayName("The guard refuses any database that is not a known throwaway")
	public void refusesADatabaseThatIsNotAllowlisted() {
		// The allowlist is the only thing standing between this class and a production database.
		assertThrows(IllegalStateException.class,
				() -> TestDataPurger.assertPurgeAllowed("jdbc:postgresql://prod.example.com:5432/liquido-prod"));

		// ... and it must still permit the ones we actually use, or the sweep is useless.
		TestDataPurger.assertPurgeAllowed("jdbc:postgresql://localhost:5432/LIQUIDO-TEST");
		TestDataPurger.assertPurgeAllowed("jdbc:postgresql://localhost:5432/liquido-int?sslmode=disable");
		assertEquals("liquido-int",
				TestDataPurger.databaseNameOf("jdbc:postgresql://localhost:5432/liquido-int?sslmode=disable"));
	}
}
