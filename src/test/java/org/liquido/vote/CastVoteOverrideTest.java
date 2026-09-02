package org.liquido.vote;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.TestFixtures;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>The level-0-only scoping of the "no changing your vote" rule</h1>
 *
 * {@code CastVoteService.castVoteRec()} now rejects a second DIRECT (level 0) cast for the same
 * voter and poll (see {@link BallotUniqueConstraintTest#reVotingIsRejected}). That check is
 * deliberately scoped to level 0 on both sides -- not to "any equal level" -- because level &gt; 0
 * ballots are never a delegee's own decision to begin with: they are always a proxy's cast cascading
 * to them. Two guarantees depend on that scoping remaining exactly that narrow, and this class
 * verifies both directly.
 */
@QuarkusTest
@DisplayName("A voter's own vote can still override a proxy's, and a proxy switch can still update")
public class CastVoteOverrideTest {

	@Inject
	LiquidoTestUtils util;

	@Test
	@DisplayName("A delegee's own first direct vote overrides the ballot their proxy already cast for them")
	public void votersOwnFirstDirectVoteOverridesProxysCascade() {
		// GIVEN a fresh team: an admin (will be the proxy) and a member (will be the delegee)
		TeamDataResponse teamRes = util.createFreshTeam("OverrideProxy");
		UserEntity admin = teamRes.user;
		UserEntity member = util.joinTeam(teamRes.team.getInviteCode(), null).user;

		TeamDataResponse adminRes = util.devLogin(admin.email);
		PollEntity poll = util.createPoll("Poll for proxy-override test", adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 2);
		poll = util.startVotingPhase(poll.getId(), adminRes.jwt);
		Long pollId = poll.getId();
		List<Long> proxyOrder = poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		List<Long> ownOrder = proxyOrder.reversed();

		// AND member delegates to admin, who accepts
		TeamDataResponse memberRes = util.devLogin(member.email);
		util.delegateTo(admin, memberRes.jwt);
		adminRes = util.devLogin(admin.email);
		util.acceptDelegationRequests(util.getDelegationRequestIds(adminRes.jwt), adminRes.jwt);

		// WHEN the proxy casts a vote -- this cascades a level-1 ballot to the delegee
		util.castVote(pollId, proxyOrder, util.getVoterToken(pollId, adminRes.jwt));
		memberRes = util.devLogin(member.email);
		BallotEntity cascadedBallot = util.getBallotOfCurrentUser(pollId, memberRes.jwt);
		assertEquals(1, cascadedBallot.getLevel(), "the delegee's ballot must be at level 1, cast by the proxy");

		// AND THEN the delegee casts their OWN direct vote for the first time
		util.castVote(pollId, ownOrder, util.getVoterToken(pollId, memberRes.jwt));

		// THEN the delegee's ballot is now their OWN, at level 0 -- overriding the proxy's cascade.
		// This must succeed: it is NOT "the delegee changing an already-cast vote", it is their first
		// ever direct vote, and per the whitepaper it must always win over a proxy's vote on their behalf.
		BallotEntity ownBallot = util.getBallotOfCurrentUser(pollId, memberRes.jwt);
		assertEquals(0, ownBallot.getLevel(), "the delegee's own direct vote must now be at level 0");
		List<Long> storedOrder = ownBallot.getVoteOrder().stream().map(LiquidoBaseEntity::getId).toList();
		assertEquals(ownOrder, storedOrder, "the ballot must hold the delegee's OWN ranking, not the proxy's");
	}

	@Test
	@DisplayName("A delegee switching to a new proxy still gets the new proxy's cascaded vote")
	public void reDelegationToANewProxyStillUpdatesTheCascade() {
		// GIVEN a fresh team with two admins added as further members, so both can act as proxies,
		// plus the team's own admin as the delegee who will switch between them.
		TeamDataResponse teamRes = util.createFreshTeam("ReDelegate");
		UserEntity delegee = teamRes.user;
		UserEntity proxyOne = util.joinTeam(teamRes.team.getInviteCode(), null).user;
		UserEntity proxyTwo = util.joinTeam(teamRes.team.getInviteCode(), null).user;

		TeamDataResponse delegeeRes = util.devLogin(delegee.email);
		PollEntity poll = util.createPoll("Poll for re-delegation test", delegeeRes.jwt);
		poll = util.seedRandomProposals(poll, delegeeRes.team, 3);
		poll = util.startVotingPhase(poll.getId(), delegeeRes.jwt);
		Long pollId = poll.getId();
		List<Long> allIds = poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		List<Long> proxyOneOrder = List.of(allIds.get(0), allIds.get(1), allIds.get(2));
		List<Long> proxyTwoOrder = List.of(allIds.get(2), allIds.get(1), allIds.get(0));

		// WHEN the delegee delegates to proxyOne, who accepts and votes -- cascading a level-1 ballot
		util.delegateTo(proxyOne, delegeeRes.jwt);
		TeamDataResponse proxyOneRes = util.devLogin(proxyOne.email);
		util.acceptDelegationRequests(util.getDelegationRequestIds(proxyOneRes.jwt), proxyOneRes.jwt);
		util.castVote(pollId, proxyOneOrder, util.getVoterToken(pollId, proxyOneRes.jwt));

		delegeeRes = util.devLogin(delegee.email);
		BallotEntity fromProxyOne = util.getBallotOfCurrentUser(pollId, delegeeRes.jwt);
		assertEquals(1, fromProxyOne.getLevel());

		// AND THEN the delegee switches their delegation to proxyTwo, who accepts and votes.
		// proxyTwo's cascade also lands at level 1 -- the SAME numeric level as proxyOne's now-stale
		// ballot. This is NOT the delegee changing their own vote (they never had agency over this
		// ballot at all), so it must still update, exactly like it did the first time.
		removeDelegationOf(delegeeRes.jwt);
		util.delegateTo(proxyTwo, delegeeRes.jwt);
		TeamDataResponse proxyTwoRes = util.devLogin(proxyTwo.email);
		util.acceptDelegationRequests(util.getDelegationRequestIds(proxyTwoRes.jwt), proxyTwoRes.jwt);
		util.castVote(pollId, proxyTwoOrder, util.getVoterToken(pollId, proxyTwoRes.jwt));

		delegeeRes = util.devLogin(delegee.email);
		BallotEntity fromProxyTwo = util.getBallotOfCurrentUser(pollId, delegeeRes.jwt);
		assertEquals(1, fromProxyTwo.getLevel(), "still a level-1 cascade, now from the new proxy");
		List<Long> storedOrder = fromProxyTwo.getVoteOrder().stream().map(LiquidoBaseEntity::getId).toList();
		assertEquals(proxyTwoOrder, storedOrder, "the ballot must now hold proxyTwo's ranking");
		assertNotEquals(proxyOneOrder, storedOrder, "proxyOne's stale ranking must be gone");
	}

	private void removeDelegationOf(String jwt) {
		TestFixtures.sendGraphQL("mutation { removeDelegation }", null, jwt)
				.statusCode(200)
				.body("errors", nullValue());
	}
}
