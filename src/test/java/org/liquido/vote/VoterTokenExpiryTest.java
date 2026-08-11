package org.liquido.vote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expiry arithmetic for voting tokens and rights to vote.
 *
 * <p>These are plain JUnit tests - no Quarkus, no database. They exist because the
 * configured units and the code's units had drifted apart in three places:
 * <ul>
 *   <li>{@code liquido.voter-token-expiration-minutes} (20) was fed into a {@code plusHours()},
 *       so a one-time voter token lived 20 <b>hours</b>.</li>
 *   <li>{@code liquido.right-to-vote-expiration-days} (365) was fed into a {@code plusHours()}
 *       at both renewal sites, so casting a vote <b>shortened</b> a right to vote from a year
 *       to about a fortnight - a renewal that was really a downgrade.</li>
 *   <li>{@link OneTimeVotingToken#isValid()} had its comparison the wrong way round and
 *       returned true only for <b>expired</b> tokens.</li>
 * </ul>
 * A unit mix-up is invisible in a green suite unless something asserts the magnitude, so
 * that is what these do.
 */
public class VoterTokenExpiryTest {

	/** Same package, so we can build entities directly without persisting them. */
	private static OneTimeVotingToken tokenExpiringAt(LocalDateTime expiresAt, RightToVoteEntity rightToVote) {
		OneTimeVotingToken ott = new OneTimeVotingToken();
		ott.hashedVoterToken = "hashedVoterTokenForTest";
		ott.rightToVote = rightToVote;
		ott.expiresAt = expiresAt;
		return ott;
	}

	private static RightToVoteEntity validRightToVote() {
		return new RightToVoteEntity("hashedVoterInfoForTest", LocalDateTime.now().plusDays(365));
	}

	@Test
	@DisplayName("A one time voter token is valid while it has not expired yet")
	public void tokenWithFutureExpiryIsValid() {
		OneTimeVotingToken ott = tokenExpiringAt(LocalDateTime.now().plusMinutes(20), validRightToVote());

		assertTrue(ott.isValid(), "A token expiring in 20 minutes must still be valid");
	}

	@Test
	@DisplayName("An expired one time voter token is not valid")
	public void expiredTokenIsNotValid() {
		OneTimeVotingToken ott = tokenExpiringAt(LocalDateTime.now().minusMinutes(1), validRightToVote());

		assertFalse(ott.isValid(), "A token that expired a minute ago must not be valid");
	}

	@Test
	@DisplayName("A token linked to an expired right to vote is not valid")
	public void tokenWithExpiredRightToVoteIsNotValid() {
		RightToVoteEntity expiredRtv = new RightToVoteEntity("hashedVoterInfoForTest", LocalDateTime.now().minusDays(1));
		OneTimeVotingToken ott = tokenExpiringAt(LocalDateTime.now().plusMinutes(20), expiredRtv);

		assertFalse(ott.isValid(), "Even a fresh token is worthless without a valid right to vote");
	}

	@Test
	@DisplayName("A token with no right to vote at all is not valid")
	public void tokenWithoutRightToVoteIsNotValid() {
		OneTimeVotingToken ott = tokenExpiringAt(LocalDateTime.now().plusMinutes(20), null);

		assertFalse(ott.isValid());
	}

	@Test
	@DisplayName("Renewing a right to vote extends it by DAYS, not hours")
	public void renewExpiryUsesDays() {
		RightToVoteEntity rightToVote = new RightToVoteEntity("hashedVoterInfoForTest", LocalDateTime.now().plusDays(1));

		rightToVote.renewExpiry(365);

		LocalDateTime expiresAt = rightToVote.getExpiresAt();
		// 365 hours would land ~15 days out. Assert we are far beyond that, and about a year out.
		assertTrue(expiresAt.isAfter(LocalDateTime.now().plusDays(364)),
				"365 days must renew to about a year, not to 365 hours (~15 days). Got: " + expiresAt);
		assertTrue(expiresAt.isBefore(LocalDateTime.now().plusDays(366)),
				"Renewal overshot a year. Got: " + expiresAt);
	}

	@Test
	@DisplayName("Renewing always extends a right to vote, it never shortens one")
	public void renewExpiryNeverShortens() {
		// This is the case that was actually broken: a right to vote created with 365 days,
		// then renewed on the first vote to 365 HOURS - i.e. voting cost the voter 11 months.
		RightToVoteEntity rightToVote = new RightToVoteEntity("hashedVoterInfoForTest", LocalDateTime.now().plusDays(300));
		LocalDateTime before = rightToVote.getExpiresAt();

		rightToVote.renewExpiry(365);

		assertTrue(rightToVote.getExpiresAt().isAfter(before),
				"Casting a vote must extend the right to vote, never shorten it");
	}
}
