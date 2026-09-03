package org.liquido;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.liquido.delegation.DelegationEntity;
import org.liquido.delegation.DelegationService;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.Lson;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.CastVoteResponse;
import org.liquido.vote.OneTimeVotingToken;
import org.liquido.vote.RightToVoteEntity;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


/**
 * Tests for most important use cases.
 * These tests call the GraphQL API
 */
@QuarkusTest
public class UseCaseTests {

	@Inject
	LiquidoTestUtils util;

	@Inject
	LiquidoConfig config;

	@Inject
	DelegationService delegationService;

	@Inject
	JwtTokenUtils jwtTokenUtils;

	/**
	 * Check that a voter can vote in two separate polls.
	 * This actually was an interesting bug: Initially I wanted to assign exactly one voterToken to each voter.
	 * That can then be used to vote anonymously. And that can also be used to become a public proxy.
	 * But this had several disadvantages:
	 *  -
	 *
	 * <p>Runs in its OWN fresh team. Nothing here needs the shared seed: the test builds its two polls
	 * and their proposals over HTTP anyway, so a two-member throwaway team serves it exactly as well.
	 * Keeping it out of the seed team means a full suite run leaves {@code testTeam4711} byte-for-byte
	 * as the generator produced it, which makes "did anything change the seed?" a question with a
	 * yes/no answer instead of a diff to interpret.
	 */
	@Test
	@TestTransaction
	public void castVoteInTwoPolls() {
		// A fresh team with two members: its admin, plus one joiner. Two is what
		// seedRandomProposals(poll, team, 2) needs, because it gives each proposal a different author.
		TeamDataResponse adminRes = util.createFreshTeam("CastVoteTwoPolls");
		util.joinTeam(adminRes.team.getInviteCode(), null);
		TeamEntity team = util.loadOwnTeam(adminRes.jwt);   // reload so the new member is in the set

		// GIVEN two polls in voting
		PollEntity poll1;
		poll1 = util.createPoll("Poll1 to test voting in two polls", adminRes.jwt);
		poll1 = util.seedRandomProposals(poll1, team, 2);
		poll1 = util.startVotingPhase(poll1.getId(), adminRes.jwt);

		PollEntity poll2;
		poll2 = util.createPoll("Poll2 to test voting in two polls", adminRes.jwt);
		poll2 = util.seedRandomProposals(poll2, team, 2);
		poll2 = util.startVotingPhase(poll2.getId(), adminRes.jwt);

		// WHEN cast vote in poll1
		String voterToken1 = util.getVoterToken(poll1.id, adminRes.jwt);
		List<Long> voteOrderIds1 = poll1.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		CastVoteResponse castVoteResponse1 = util.castVote(poll1.id, voteOrderIds1, voterToken1);
		assertNotNull(castVoteResponse1.getBallot().checksum, "Vote in poll1 should have returned a ballot with a checksum");

		// THEN ballot1 is valid
		BallotEntity ballot1 = util.verifyBallot(poll1.id, castVoteResponse1.getBallot().checksum);
		assertNotNull(ballot1);

		BallotEntity ballotOfCurrentUser = util.getBallotOfCurrentUser(poll1.id, adminRes.jwt);


		// WHEN cast vote in poll2
		String voterToken2 = util.getVoterToken(poll2.id, adminRes.jwt);
		List<Long> voteOrderIds2 = poll2.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		CastVoteResponse castVoteResponse2 = util.castVote(poll2.id, voteOrderIds2, voterToken2);
		assertNotNull(castVoteResponse2.getBallot().checksum, "Vote in poll2 should have returned a ballot with a checksum");

		// THEN ballot2 is also valid
		BallotEntity ballot2 = util.verifyBallot(poll2.id, castVoteResponse2.getBallot().checksum);
		assertNotNull(ballot2);

		// AND this test's own voterTokens have been consumed.
		// Deliberately scoped per poll. This used to assert the WHOLE voting_tokens table was empty,
		// which is a global invariant that a local test has no business claiming: one abandoned token
		// anywhere - a crashed run, or any future test that fetches a token to check an error path -
		// broke this permanently, and pointed the blame at this test.
		assertEquals(0, OneTimeVotingToken.count("poll.id = ?1", poll1.getId()),
				"voterToken for poll1 should have been consumed by casting the vote");
		assertEquals(0, OneTimeVotingToken.count("poll.id = ?1", poll2.getId()),
				"voterToken for poll2 should have been consumed by casting the vote");
	}

	/**
	 * Deliberately runs in its OWN fresh team, not in the shared seed team.
	 *
	 * Delegating is not "appending data" -- it rewrites a relationship between two seed users, and
	 * {@code @TestTransaction} does not roll back mutations made over HTTP, so the delegation would
	 * survive the run. Two things then go wrong for everybody else: the seed admin permanently becomes
	 * the seed member's proxy, so any later test in which the admin votes silently also creates a
	 * level-1 ballot for the member; and this test's own {@code delegationCount == voteCount} assertion
	 * only holds while no delegee has already voted, which the accumulated delegations make less true
	 * every run.
	 *
	 * A fresh team with one joined member is exactly the two-member team {@code seedRandomProposals(…, 2)}
	 * needs, and this test builds its own poll and proposals over HTTP anyway -- so isolation costs two
	 * extra calls and nothing else.
	 */
	@Test
	@TestTransaction
	public void proxyCastsVoteForVoter() {
		TeamDataResponse teamRes = util.createFreshTeam("ProxyVote");
		UserEntity admin = teamRes.user;
		UserEntity member = util.joinTeam(teamRes.team.getInviteCode(), null).user;

		// GIVEN a poll in voting (created by admin)
		TeamDataResponse adminRes = util.devLogin(admin.email);
		PollEntity poll;
		poll = util.createPoll("Poll to test vote of proxy", adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 2);
		poll = util.startVotingPhase(poll.getId(), adminRes.jwt);

		// WHEN member delegates to admin as his proxy (admin is not a public proxy, so this is only
		// a pending request -- see DelegationService.delegateTo)
		TeamDataResponse memberRes = util.devLogin(member.email);
		util.delegateTo(admin, memberRes.jwt);

		// AND admin accepts that delegation request
		adminRes = util.devLogin(admin.email);
		List<Long> requestIds = util.getDelegationRequestIds(adminRes.jwt);
		util.acceptDelegationRequests(requestIds, adminRes.jwt);

		//  AND admin casts a vote
		adminRes = util.devLogin(admin.email);
		String voterToken = util.getVoterToken(poll.id, adminRes.jwt);
		List<Long> voteOrderIds = poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		CastVoteResponse castVoteResponse = util.castVote(poll.getId(), voteOrderIds, voterToken);

		// THEN the proxy's own ballot is created
		assertNotNull(castVoteResponse.getBallot(), "Proxy should have received a ballot");
		//  AND the proxy's vote is counted for all his delegees (also transitive ones)
		long delegationCount = util.getDelegationCount(adminRes.user, adminRes.jwt);
		assertEquals(delegationCount, castVoteResponse.getVoteCount(), "Vote should have been counted for " + delegationCount + " delegee(s)");

		// AND this vote is also counted for all delegee)
		memberRes = util.devLogin(member.email);
		BallotEntity memberBallot = util.getBallotOfCurrentUser(poll.id, memberRes.jwt);
		assertNotNull(memberBallot, "Member should have a ballot cast by their proxy");
		
		List<Long> memberVoteOrderIds = memberBallot.getVoteOrder().stream().map(LiquidoBaseEntity::getId).toList();
		assertEquals(voteOrderIds, memberVoteOrderIds, "Member's ballot should have the same vote order as the proxy's");
		assertEquals(1, memberBallot.getLevel(), "Member's ballot should be at level 1 (delegated)");
	}

	/**
	 * Regression test for A3 (delegation bugs): delegating to a non-public proxy must only create a
	 * pending request -- not an immediate delegation. Before the fix, the public/non-public branches
	 * in DelegationService.delegateTo were inverted, so this case was immediate instead.
	 */
	@Test
	@TestTransaction
	public void delegateToNonPublicProxy_createsPendingRequestOnly() {
		// A dedicated, isolated team -- not the shared seeded one -- so this test can't shift member
		// ordering/roles (TeamEntity.members is an unordered Set) that other tests depend on.
		TeamEntity team = util.createFreshTeam("DelegateNonPublic" + new Date().getTime()).team;
		String inviteCode = team.inviteCode;
		UserEntity delegator = util.joinTeam(inviteCode, null).user;
		UserEntity proxy = util.joinTeam(inviteCode, null).user;

		TeamDataResponse delegatorRes = util.devLogin(delegator.email);
		util.delegateTo(proxy, delegatorRes.jwt);

		RightToVoteEntity delegatorRTV = RightToVoteEntity.findByVoterAndTeam(delegator, team, config)
				.orElseThrow(() -> new RuntimeException("delegator has no RightToVote"));
		assertNull(delegatorRTV.getDelegatedTo(), "Delegation to a non-public proxy must NOT be immediate");

		long pendingRequestsFromDelegator = DelegationEntity.findDelegationRequestsTo(proxy).stream()
				.filter(d -> d.getFromUser().equals(delegator)).count();
		assertEquals(1, pendingRequestsFromDelegator, "Delegating to a non-public proxy must create exactly one pending request");
	}

	/**
	 * Regression test for A3: delegating to a public proxy must be immediate (that's the whole point
	 * of being a public proxy -- delegees don't need to wait for an explicit accept).
	 */
	@Test
	public void delegateToPublicProxy_isImmediate() {
		TeamEntity team = util.createFreshTeam("DelegatePublic" + new Date().getTime()).team;
		String inviteCode = team.inviteCode;
		UserEntity delegator = util.joinTeam(inviteCode, null).user;
		UserEntity proxy = util.joinTeam(inviteCode, null).user;

		// Committed in its own real transaction (not @TestTransaction, which never commits) --
		// the delegateTo() call below is a separate HTTP request on a separate transaction/thread
		// and would otherwise never see this write.
		QuarkusTransaction.requiringNew().run(() -> {
			RightToVoteEntity proxyRTV = RightToVoteEntity.findByVoterAndTeam(proxy, team, config)
					.orElseThrow(() -> new RuntimeException("proxy has no RightToVote"));
			proxyRTV.setPublicProxy(proxy);
			proxyRTV.persist();
		});

		TeamDataResponse delegatorRes = util.devLogin(delegator.email);
		util.delegateTo(proxy, delegatorRes.jwt);

		QuarkusTransaction.requiringNew().run(() -> {
			RightToVoteEntity proxyRTV = RightToVoteEntity.findByVoterAndTeam(proxy, team, config)
					.orElseThrow(() -> new RuntimeException("proxy has no RightToVote"));
			RightToVoteEntity delegatorRTV = RightToVoteEntity.findByVoterAndTeam(delegator, team, config)
					.orElseThrow(() -> new RuntimeException("delegator has no RightToVote"));
			assertNotNull(delegatorRTV.getDelegatedTo(), "Delegation to a public proxy must be immediate");
			assertEquals(proxyRTV.getHashedVoterInfo(), delegatorRTV.getDelegatedTo().getHashedVoterInfo());
		});
	}

	/**
	 * Regression test for A3 (delegation request theft): acceptDelegationRequests must only ever accept
	 * requests actually addressed to the caller. Before the fix, it fetched delegation rows by arbitrary
	 * ID with no check they were addressed to the caller, so acceptDelegationRequests([1..1000]) could
	 * absorb every pending delegation request in the system.
	 */
	@Test
	@TestTransaction
	public void acceptDelegationRequests_cannotStealOthersRequests() throws org.liquido.util.LiquidoException {
		TeamEntity team = util.createFreshTeam("DelegationTheft" + new Date().getTime()).team;
		String inviteCode = team.inviteCode;
		UserEntity delegator = util.joinTeam(inviteCode, null).user;
		UserEntity intendedProxy = util.joinTeam(inviteCode, null).user;
		UserEntity attacker = util.joinTeam(inviteCode, null).user;

		TeamDataResponse delegatorRes = util.devLogin(delegator.email);
		util.delegateTo(intendedProxy, delegatorRes.jwt);  // non-public proxy -> creates a pending request

		DelegationEntity request = DelegationEntity.findDelegationRequestsTo(intendedProxy).stream()
				.filter(d -> d.getFromUser().equals(delegator)).findFirst()
				.orElseThrow(() -> new RuntimeException("Expected a pending delegation request to intendedProxy"));

		// WHEN an unrelated user (attacker) tries to accept that request as if it were addressed to them
		TeamDataResponse attackerRes = util.devLogin(attacker.email);
		String query = "mutation acceptDelegationRequests($ids: [BigInteger!]!) { acceptDelegationRequests(delegationRequestIds: $ids) }";
		Lson vars = new Lson("ids", List.of(request.getId()));
		TestFixtures.sendGraphQL(query, vars, attackerRes.jwt);

		// THEN nothing was stolen: attacker gained no delegation, and the delegator is still not delegated.
		// countDelegationsTo() is called DIRECTLY here rather than over HTTP, so it has no JWT to read a
		// team from -- and a delegation count is now per team, because a right to vote is. Give it the
		// team context the equivalent HTTP call would have carried.
		jwtTokenUtils.setCurrentUserAndTeam(attacker, TeamEntity.findById(team.id));
		long attackerDelegationCount = delegationService.countDelegationsTo(attacker);
		assertEquals(0, attackerDelegationCount, "Attacker must not have gained a delegation from someone else's request");
		RightToVoteEntity delegatorRTV = RightToVoteEntity.findByVoterAndTeam(delegator, team, config)
				.orElseThrow(() -> new RuntimeException("delegator has no RightToVote"));
		assertNull(delegatorRTV.getDelegatedTo(), "Delegator's vote must still not be delegated to anyone");
	}

}