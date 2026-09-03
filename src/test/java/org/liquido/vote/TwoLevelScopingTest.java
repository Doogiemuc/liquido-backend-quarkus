package org.liquido.vote;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>The two levels of scoping, and what each one buys</h1>
 *
 * The anonymity architecture rests on two derived values with deliberately different scopes, and
 * each defeats a different attacker. This class asserts both, because neither is visible in a green
 * suite otherwise -- a build with the scoping silently removed passes every other test in the
 * project, since nothing else cares how many rights to vote a person holds.
 *
 * <ul>
 *   <li><b>Layer 1, per team.</b> A person in two teams holds two unrelated rights to vote, so two
 *       unrelated LIQUIDO teams cannot correlate their members even with full access to both
 *       databases.</li>
 *   <li><b>Layer 3, per poll.</b> One voter's ballots in two polls of the SAME team carry two
 *       unrelated pseudonyms, so an attacker holding the whole database cannot group them into a
 *       voting history -- which is what a direct foreign key from ballot to right-to-vote would have
 *       handed them for free.</li>
 * </ul>
 *
 * "Unrelated" here means only what it can mean without breaking a keyed hash: the values differ, and
 * neither is derivable from the other without the server secret. The server itself can still link
 * everything -- that is the accepted and documented trade (whitepaper 5.3), and no test can assert
 * it away.
 */
@QuarkusTest
@DisplayName("Rights to vote are scoped per team, ballot pseudonyms per poll")
public class TwoLevelScopingTest {

	@Inject
	LiquidoTestUtils util;

	@Inject
	LiquidoConfig config;

	@Test
	@DisplayName("One person in two teams holds two unrelated rights to vote")
	public void aPersonInTwoTeamsHoldsTwoUnrelatedRightsToVote() {
		// GIVEN one human being who is a member of two completely unrelated teams
		TeamDataResponse teamA = util.createFreshTeam("ScopingTeamA");
		TeamDataResponse joinedA = util.joinTeam(teamA.team.getInviteCode(), null);
		UserEntity person = joinedA.user;

		TeamDataResponse teamB = util.createFreshTeam("ScopingTeamB");
		TeamDataResponse joinedB = util.joinTeamAsRegisteredUser(teamB.team.getInviteCode(), person, joinedA.jwt);

		assertEquals(person.id, joinedB.user.id, "must be the same person, not a newly created user");
		assertNotEquals(teamA.team.id, teamB.team.id, "must really be two different teams");

		// WHEN we ask for their right to vote in each team
		RightToVoteEntity inTeamA = rightToVoteOf(person, teamA.team);
		RightToVoteEntity inTeamB = rightToVoteOf(person, teamB.team);

		// THEN they are two distinct rights to vote, and nothing about one reveals the other.
		// Before per-team scoping this was ONE row shared across every team the person belonged to,
		// so two teams comparing databases could see the identical value and know it was one person.
		assertNotEquals(inTeamA.getHashedVoterInfo(), inTeamB.getHashedVoterInfo(),
				"A person in two teams must hold two UNRELATED rights to vote, or two teams can " +
				"correlate their members by comparing databases");
		assertEquals(teamA.team.id, inTeamA.getTeam().id, "the right to vote must be scoped to its team");
		assertEquals(teamB.team.id, inTeamB.getTeam().id, "the right to vote must be scoped to its team");

		// AND each records which server secret produced it, so a leak is recoverable rather than terminal
		assertEquals(config.hashSecretVersion(), inTeamA.getKeyVersion(),
				"the key version must be recorded, or a rotated secret cannot be told from the current one");
	}

	@Test
	@DisplayName("One voter's ballots in two polls of one team carry two unrelated pseudonyms")
	public void ballotsInTwoPollsCarryUnrelatedPseudonyms() throws Exception {
		// GIVEN one voter and two polls in the SAME team
		TeamDataResponse team = util.createFreshTeam("ScopingTwoPolls");
		Long pollOne = aPollInVoting(team, "Scoping poll one");
		Long pollTwo = aPollInVoting(team, "Scoping poll two");

		// WHEN they vote in both
		castVoteIn(pollOne, team.jwt);
		castVoteIn(pollTwo, team.jwt);

		// THEN the two ballots carry different pseudonyms, neither of which is the voter's right to vote
		RightToVoteEntity rightToVote = rightToVoteOf(team.user, team.team);
		String pseudonymOne = derive(rightToVote, pollOne);
		String pseudonymTwo = derive(rightToVote, pollTwo);

		assertNotEquals(pseudonymOne, pseudonymTwo,
				"The same voter in two polls must derive two UNRELATED pseudonyms, or an attacker with " +
				"the database can group one voter's ballots into a voting history");
		assertNotEquals(rightToVote.getHashedVoterInfo(), pseudonymOne,
				"a ballot must never carry the right to vote itself");

		// AND those are really the values the ballots were stored under
		assertTrue(BallotEntity.findByPollAndPseudonym(pollById(pollOne), pseudonymOne).isPresent(),
				"the ballot in poll one must be findable by its derived pseudonym");
		assertTrue(BallotEntity.findByPollAndPseudonym(pollById(pollTwo), pseudonymTwo).isPresent(),
				"the ballot in poll two must be findable by its derived pseudonym");

		// AND the cross lookup finds nothing: poll one's pseudonym means nothing in poll two.
		assertTrue(BallotEntity.findByPollAndPseudonym(pollById(pollTwo), pseudonymOne).isEmpty(),
				"a pseudonym from one poll must not resolve to a ballot in another");
	}

	// ------------------------------------------------------------------ helpers

	private Long aPollInVoting(TeamDataResponse team, String title) {
		PollEntity poll = util.createPoll(title, team.jwt);
		poll = util.addProposal(poll.getId(), title + " option A", "First alternative for " + title, "hand-peace", team.jwt);
		poll = util.addProposal(poll.getId(), title + " option B", "Second alternative for " + title, "hand-rock", team.jwt);
		return util.startVotingPhase(poll.getId(), team.jwt).getId();
	}

	private void castVoteIn(Long pollId, String jwt) {
		PollEntity poll = util.getPoll(pollId, jwt);
		List<Long> voteOrder = poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		util.castVote(pollId, voteOrder, util.getVoterToken(pollId, jwt));
	}

	private RightToVoteEntity rightToVoteOf(UserEntity voter, TeamEntity team) {
		return QuarkusTransaction.requiringNew().call(() ->
				RightToVoteEntity.findByVoterAndTeam(voter, team, config)
						.orElseThrow(() -> new AssertionError("no right to vote for this voter in team " + team.id)));
	}

	private String derive(RightToVoteEntity rightToVote, Long pollId) {
		return QuarkusTransaction.requiringNew().call(() -> rightToVote.deriveBallotPseudonym(pollId, config));
	}

	private PollEntity pollById(Long pollId) {
		return QuarkusTransaction.requiringNew().call(() -> PollEntity.findById(pollId));
	}
}
