package org.liquido;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.liquido.delegation.DelegationEntity;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.user.UserEntity;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.CastVoteResponse;
import org.liquido.vote.RightToVoteEntity;
import org.liquido.vote.VoterTokenEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


/**
 * Tests for most important use cases.
 * These tests call the GraphQL API
 */
@QuarkusTest
public class UseCaseTests {

	@Inject
	LiquidoTestUtils util;

	/**
	 * Check that a voter can vote in two separate polls.
	 * This actually was an interesting bug: Initially I wanted to assign exactly one voterToken to each voter.
	 * That can then be used to vote anonymously. And that can also be used to become a public proxy.
	 * But this had several disadvantages:
	 *  -
	 */
	@Test
	@TestTransaction
	public void castVoteInTwoPolls() {
		UserEntity admin = util.getRandomAdmin();

		TeamDataResponse adminRes = util.devLogin(admin.email);

		// GIVEN two polls in voting
		PollEntity poll1;
		poll1 = util.createPoll("Poll1 to test voting in two polls", adminRes.jwt);
		poll1 = util.seedRandomProposals(poll1, adminRes.team, 2);
		poll1 = util.startVotingPhase(poll1.getId(), adminRes.jwt);

		PollEntity poll2;
		poll2 = util.createPoll("Poll2 to test voting in two polls", adminRes.jwt);
		poll2 = util.seedRandomProposals(poll2, adminRes.team, 2);
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

		// AND all voterTokens have been consumed
		assertEquals(0, VoterTokenEntity.findAll().stream().count(), "All one time voterTokens should have been consumed.");
	}

	@Test
	@TestTransaction
	public void proxyCastsVoteForVoter() {
		TeamEntity team = util.getRandomTeam();
		UserEntity admin = team.getFirstAdmin();
		UserEntity member = team.getMembers().stream().filter(m -> m.getRole().equals(TeamMemberEntity.Role.MEMBER)).findFirst()
				.orElseThrow(() -> new RuntimeException("Need a member in team "+team)).getUser();

		// GIVEN a poll in voting (created by admin)
		TeamDataResponse adminRes = util.devLogin(admin.email);
		PollEntity poll;
		poll = util.createPoll("Poll to test vote of proxy", adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 2);
		poll = util.startVotingPhase(poll.getId(), adminRes.jwt);

		// AND admin is a public proxy
		//TODO!

		// WHEN member delegates to admin as his proxy
		TeamDataResponse memberRes = util.devLogin(member.email);
		util.delegateTo(admin, memberRes.jwt);

		//  AND admin casts a vote
		adminRes = util.devLogin(admin.email);
		String voterToken = util.getVoterToken(poll.id, adminRes.jwt);

		String hashedVoterToken = DigestUtils.sha3_256Hex(voterToken + poll.id + "hashSecretTest");
		System.out.println("getVoterToken: VoterTokenEntity.buildAndPersist " + voterToken + " =>  hashedVoterToken="+hashedVoterToken);

		List<Long> voteOrderIds = poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		util.castVote(poll.getId(), voteOrderIds, voterToken);

		// THEN this vote is also counted for member
		memberRes = util.devLogin(member.email);

		System.out.println("=========== all RightToVotes");
		RightToVoteEntity.findAll().stream().forEach(rtv -> {
			System.out.println(((RightToVoteEntity)rtv).hashedVoterInfo + " => " + rtv.toString());
		});
		System.out.println("=========== all VoterTokens");
		VoterTokenEntity.findAll().stream().forEach(token -> {
			System.out.println(token.toString());
		});
		System.out.println("=========== all Delegations to a proxy");
		DelegationEntity.findAll().stream().forEach(delegation -> {
			System.out.println(delegation.toString());
		});
		System.out.println("=========== all Ballots");
		BallotEntity.findAll().stream().forEach(ballot -> {
			System.out.println(ballot.toString());
		});


		/*
		BallotEntity ballot = util.verifyBallot(poll.id, ??????????)
		assertNotNull(ballot);
		List<Long> ballotVoteOrderIds = ballot.getVoteOrder().stream().map(BaseEntity::getId).toList();
		assert ballotVoteOrderIds.equals(voteOrderIds) : "vote did not return same list of voteOrderIDs";

		 */
	}


}