package org.liquido;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.liquido.model.BaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.vote.CastVoteResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;


@QuarkusTest
public class UseCaseTests {

	@Inject
	LiquidoTestUtils util;

	/**
	 * Check that a voter can vote in two separate polls.
	 *
	 * This actually was an interesting bug: Initially I wanted to assign exactly one voterToken to each voter.
	 * That can then be used to vote anonymously. And that can also be used to become a public proxy.
	 * But this had several disadvantages:
	 *  -
	 */
	@Test
	public void castVoteInTwoPolls() {
		UserEntity admin = LiquidoTestUtils.getRandomAdmin();

		TeamDataResponse adminRes = util.devLogin(admin.email);

		// Create Poll in VOTING with started voting phase
		PollEntity poll1;
		poll1 = util.createPoll("Poll1 to test voting in two polls", adminRes.jwt);
		poll1 = util.seedRandomProposals(poll1, adminRes.team, 2);
		poll1 = util.startVotingPhase(poll1.getId(), adminRes.jwt);

		PollEntity poll2;
		poll2 = util.createPoll("Poll2 to test voting in two polls", adminRes.jwt);
		poll2 = util.seedRandomProposals(poll2, adminRes.team, 2);
		poll2 = util.startVotingPhase(poll2.getId(), adminRes.jwt);

		String voterToken = util.getVoterToken("tokenSecret", adminRes.jwt);

		// Cast vote in poll1
		List<Long> voteOrderIds1 = poll1.getProposals().stream().map(BaseEntity::getId).toList();
		CastVoteResponse castVoteResponse = util.castVote(poll1.id, voteOrderIds1, voterToken);

		assertNotNull(castVoteResponse.getBallot(), "Vote in poll1 should have returned a ballot");

		// Now also cast a vote in poll2 WITH THE SAME voterToken
		List<Long> voteOrderIds2 = poll2.getProposals().stream().map(BaseEntity::getId).toList();
		util.castVote(poll2.id, voteOrderIds2, voterToken);
	}
}