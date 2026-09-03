package org.liquido.vote;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>An expired right to vote must be recoverable, or expiry is a permanent disenfranchisement</h1>
 *
 * A right to vote carries an {@code expiresAt}, and {@link RightToVoteEntity#isValid()} reports
 * whether it has lapsed. The intent is staleness pruning: someone who has not voted in a year should
 * not sit in the delegation graph as a live proxy target forever.
 *
 * <h2>The bug this pins down</h2>
 *
 * Expiry had no way back. {@code renewExpiry()} was reachable only from the two casting paths, and
 * both of those refuse an already-expired right to vote before they get there. The only other writer,
 * {@code grantRightToVote()}, short-circuits when a row already exists -- correctly, since it must
 * not write a second row under the same derived primary key. So the states formed a trap:
 *
 * <pre>
 *   valid --(365 days pass)--&gt; expired --(no transition exists)--&gt; expired forever
 * </pre>
 *
 * A team member who simply did not vote for a year was locked out permanently, recoverable only by
 * editing the database by hand. That is a disenfranchisement bug, and in a voting system it is the
 * serious kind: it removes a voter silently, and it removes exactly the least engaged voters.
 *
 * <h2>Why membership is the right thing to re-check</h2>
 *
 * The entitlement to vote is team MEMBERSHIP -- a right to vote is granted when someone joins a team
 * and is derived from {@code HMAC(secret, email | teamId)}. Expiry is therefore a staleness marker
 * on a derived value, not a withdrawal of the entitlement itself. Re-deriving it for someone who is
 * still a member restores what membership already grants them; doing it for a non-member would
 * manufacture an entitlement that no longer exists. So renewal is gated on current membership, which
 * is also what keeps a removed member's stale right to vote dead.
 */
@QuarkusTest
@DisplayName("An expired right to vote is renewed for a current member, and only for one")
public class RightToVoteExpiryRenewalTest {

	@Inject
	LiquidoTestUtils util;

	@Inject
	LiquidoConfig config;

	@Test
	@DisplayName("A member who let their right to vote lapse can vote again")
	public void anExpiredRightToVoteIsRenewedForACurrentMember() {
		// GIVEN a team member with a poll to vote in
		TeamDataResponse team = util.createFreshTeam("RtvRenewal");
		PollEntity poll = util.createPoll("Poll for a lapsed member", team.jwt);
		poll = util.addProposal(poll.getId(), "Renewal option A",
				"The first alternative in this poll, described at sufficient length.", "hand-peace", team.jwt);
		poll = util.addProposal(poll.getId(), "Renewal option B",
				"The second alternative in this poll, described at sufficient length.", "hand-rock", team.jwt);
		poll = util.startVotingPhase(poll.getId(), team.jwt);
		Long pollId = poll.getId();

		// AND their right to vote has lapsed, exactly as it would after right-to-vote-expiration-days
		LocalDateTime expiredAt = LocalDateTime.now().minusDays(1);
		expireRightToVote(team.user, team.team, expiredAt);

		assertFalse(readRightToVote(team.user, team.team).isValid(),
				"precondition: the right to vote must actually be expired for this test to mean anything");

		// WHEN they come back and ask for a voter token.
		// Before the fix this threw CANNOT_CREATE_VOTING_TOKEN, "Your right to vote has expired." --
		// and nothing anywhere could ever move them out of that state again.
		String voterToken = util.getVoterToken(pollId, team.jwt);

		assertNotNull(voterToken, "a current team member must be able to obtain a voter token again");
		assertFalse(voterToken.isBlank(), "a current team member must be able to obtain a voter token again");

		// THEN the right to vote is live again, and the vote actually goes through
		assertTrue(readRightToVote(team.user, team.team).isValid(),
				"Using a lapsed right to vote must renew it. If it stays expired, the voter is back in " +
				"the trap on their next visit and expiry is a permanent disenfranchisement.");

		util.castVote(pollId, poll.getProposals().stream().map(p -> p.getId()).toList(), voterToken);
	}

	@Test
	@DisplayName("An expired right to vote belonging to a non-member is NOT renewed")
	public void anExpiredRightToVoteIsNotRenewedForANonMember() {
		// GIVEN two unrelated teams, and a person who is a member of only the first
		TeamDataResponse home = util.createFreshTeam("RtvRenewalHome");
		TeamDataResponse other = util.createFreshTeam("RtvRenewalOther");

		// AND an expired right to vote for that person in the team they do NOT belong to. This is the
		// shape a removed member leaves behind: the derived row outlives the membership.
		UserEntity outsider = home.user;
		TeamEntity otherTeam = other.team;
		QuarkusTransaction.requiringNew().run(() -> {
			TeamEntity team = TeamEntity.findById(otherTeam.id);
			UserEntity user = UserEntity.findById(outsider.id);
			assertFalse(team.isMember(user), "precondition: this person must not be a member of the other team");
			RightToVoteEntity stale = RightToVoteEntity.build(user, team, config.rightToVoteExpirationDays(), config);
			stale.setExpiresAt(LocalDateTime.now().minusDays(1));
			stale.persist();
		});

		// WHEN the revival rule is applied to that stale right to vote.
		//
		// This calls renewIfMemberOf() directly rather than going through the voter-token endpoint,
		// because a non-member cannot reach that endpoint at all -- the team boundary rejects them
		// before any right to vote is looked up. The membership gate is defence in depth behind that
		// boundary, so this is the level at which it can actually be exercised.
		QuarkusTransaction.requiringNew().run(() -> {
			TeamEntity team = TeamEntity.findById(otherTeam.id);
			UserEntity user = UserEntity.findById(outsider.id);
			RightToVoteEntity stale = RightToVoteEntity.findByVoterAndTeam(user, team, config)
					.orElseThrow(() -> new AssertionError("the stale right to vote should still be findable"));

			boolean usable = stale.renewIfMemberOf(team, user, config.rightToVoteExpirationDays());

			// THEN it is refused, and left expired.
			assertFalse(usable,
					"A right to vote left behind by someone who is no longer a member must not be revived. " +
					"Renewing it would hand a non-member a vote in a team they left.");
			assertFalse(stale.isValid(),
					"A refused renewal must not have extended the expiry as a side effect.");
		});

		// AND the same rule, applied to a genuine member of their OWN team, does revive it -- so the
		// assertions above are refusing a non-member, not refusing everybody.
		expireRightToVote(home.user, home.team, LocalDateTime.now().minusDays(1));
		QuarkusTransaction.requiringNew().run(() -> {
			TeamEntity team = TeamEntity.findById(home.team.id);
			UserEntity user = UserEntity.findById(home.user.id);
			RightToVoteEntity lapsed = RightToVoteEntity.findByVoterAndTeam(user, team, config)
					.orElseThrow(() -> new AssertionError("the member should hold a right to vote"));

			assertTrue(lapsed.renewIfMemberOf(team, user, config.rightToVoteExpirationDays()),
					"a current member's lapsed right to vote must be revived by the same call that refuses a non-member");
			assertTrue(lapsed.isValid(), "and it must actually be valid afterwards");
		});
	}

	// ------------------------------------------------------------------ helpers

	/** Age a right to vote so that isValid() reports false, as the passage of time would. */
	private void expireRightToVote(UserEntity voter, TeamEntity team, LocalDateTime expiredAt) {
		QuarkusTransaction.requiringNew().run(() -> {
			RightToVoteEntity rightToVote = RightToVoteEntity
					.findByVoterAndTeam(UserEntity.findById(voter.id), TeamEntity.findById(team.id), config)
					.orElseThrow(() -> new AssertionError("the member should already hold a right to vote"));
			rightToVote.setExpiresAt(expiredAt);
			rightToVote.persist();
		});
	}

	private RightToVoteEntity readRightToVote(UserEntity voter, TeamEntity team) {
		return QuarkusTransaction.requiringNew().call(() -> RightToVoteEntity
				.findByVoterAndTeam(UserEntity.findById(voter.id), TeamEntity.findById(team.id), config)
				.orElseThrow(() -> new AssertionError("the member should hold a right to vote")));
	}
}
