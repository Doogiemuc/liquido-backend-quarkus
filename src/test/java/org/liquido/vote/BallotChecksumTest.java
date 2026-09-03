package org.liquido.vote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>P1-1: the ballot checksum must be a genuine, reproducible commitment</h1>
 *
 * Three defects made {@link BallotEntity#calcSha256Checksum()} not one, all from the security backlog:
 * <ol>
 *   <li>It ran on {@code @PostUpdate}, which fires AFTER the flush - a checksum computed there was
 *       not guaranteed to land in the same transaction as the change that produced it.</li>
 *   <li>It hashed {@code voteOrder.hashCode() + poll.hashCode()} as plain {@code int} addition,
 *       evaluated arithmetically BEFORE concatenation with the trailing String - collapsing two
 *       unrelated 32-bit values with no domain separation between "which ranking" and "which poll".</li>
 *   <li>{@link ProposalEntity} is {@code @EqualsAndHashCode(of={"title","status"})}, and
 *       {@code PollService.finishVotingPhase()} sets every proposal's status to LOST or LAW. From
 *       that moment the inputs that produced the ORIGINAL checksum no longer existed in the form
 *       they had at signing time, so neither the voter nor an auditor could ever recompute it again -
 *       exactly when a receipt is most likely to be checked.</li>
 * </ol>
 *
 * Plain JUnit, no Quarkus, no database: {@code calcSha256Checksum()} only reads fields already set
 * on its arguments, so ids are assigned directly rather than obtained by persisting.
 */
@DisplayName("The ballot checksum is a real, recomputable commitment")
public class BallotChecksumTest {

	private static PollEntity pollWithId(long id) {
		PollEntity poll = new PollEntity("Checksum test poll " + id);
		poll.id = id;
		return poll;
	}

	private static ProposalEntity proposalWithId(long id, ProposalEntity.LawStatus status) {
		ProposalEntity proposal = new ProposalEntity("Checksum option " + id, "Description of option " + id);
		proposal.id = id;
		proposal.setStatus(status);
		return proposal;
	}

	/**
	 * A ballot stores a poll-scoped pseudonym, not a right to vote. For a checksum test any stable
	 * string will do -- what matters is that the same pseudonym yields the same checksum and a
	 * different one does not.
	 */
	private static String pseudonym(String value) {
		return value;
	}

	@Test
	@DisplayName("The checksum survives a proposal's status changing, e.g. when the poll finishes")
	public void checksumSurvivesProposalStatusChange() {
		PollEntity poll = pollWithId(1);
		ProposalEntity propA = proposalWithId(10, ProposalEntity.LawStatus.VOTING);
		ProposalEntity propB = proposalWithId(11, ProposalEntity.LawStatus.VOTING);
		String pseudonym = pseudonym("checksumStatusTestHash");

		BallotEntity ballot = new BallotEntity(poll, 0, List.of(propA, propB), pseudonym);
		ballot.calcSha256Checksum();
		String checksumWhileVoting = ballot.checksum;
		assertNotNull(checksumWhileVoting);

		// Simulate PollService.finishVotingPhase(): every proposal's status flips to LOST or LAW.
		// Before this fix, ProposalEntity.hashCode() included status, so this alone changed the hash
		// input and made the ORIGINAL checksum permanently unrecoverable.
		propA.setStatus(ProposalEntity.LawStatus.LAW);
		propB.setStatus(ProposalEntity.LawStatus.LOST);
		ballot.calcSha256Checksum();

		assertEquals(checksumWhileVoting, ballot.checksum,
				"Recomputing the checksum after the poll finished (proposal status changed) must " +
				"yield the SAME checksum, or a voter's receipt stops verifying the moment the poll closes.");
	}

	@Test
	@DisplayName("Two different vote orders of the same proposals get different checksums")
	public void checksumChangesWithVoteOrder() {
		PollEntity poll = pollWithId(2);
		ProposalEntity propA = proposalWithId(20, ProposalEntity.LawStatus.VOTING);
		ProposalEntity propB = proposalWithId(21, ProposalEntity.LawStatus.VOTING);
		String pseudonym = pseudonym("checksumOrderTestHash");

		BallotEntity ballotAB = new BallotEntity(poll, 0, List.of(propA, propB), pseudonym);
		ballotAB.calcSha256Checksum();

		BallotEntity ballotBA = new BallotEntity(poll, 0, List.of(propB, propA), pseudonym);
		ballotBA.calcSha256Checksum();

		assertNotEquals(ballotAB.checksum, ballotBA.checksum,
				"A different ranking of the SAME proposals must produce a different checksum");
	}

	@Test
	@DisplayName("Two different polls with the same vote order get different checksums")
	public void checksumDoesNotCollapseDistinctPolls() {
		// Regression for the arithmetic-concatenation bug: the old code hashed
		// (voteOrder.hashCode() + poll.hashCode()) + hashedVoterInfo -- plain int addition BEFORE
		// string concatenation, with no separator between "which poll" and what follows. Two
		// different polls with the same proposal ids and vote order must still produce different
		// checksums, because poll.id is now part of the hash input under its own delimiter.
		ProposalEntity propA = proposalWithId(30, ProposalEntity.LawStatus.VOTING);
		ProposalEntity propB = proposalWithId(31, ProposalEntity.LawStatus.VOTING);
		String pseudonym = pseudonym("checksumPollTestHash");

		BallotEntity ballotInPollOne = new BallotEntity(pollWithId(3), 0, List.of(propA, propB), pseudonym);
		ballotInPollOne.calcSha256Checksum();
		BallotEntity ballotInPollTwo = new BallotEntity(pollWithId(4), 0, List.of(propA, propB), pseudonym);
		ballotInPollTwo.calcSha256Checksum();

		assertNotEquals(ballotInPollOne.checksum, ballotInPollTwo.checksum,
				"The same vote order cast in two different polls must produce different checksums");
	}

	@Test
	@DisplayName("The checksum does not depend on level or on who cast the vote")
	public void checksumIsIndependentOfLevel() {
		// Documented invariant on BallotEntity.checksum: it deliberately does not depend on level,
		// so a re-cast that does not change the ranking does not change the receipt.
		PollEntity poll = pollWithId(5);
		ProposalEntity propA = proposalWithId(40, ProposalEntity.LawStatus.VOTING);
		ProposalEntity propB = proposalWithId(41, ProposalEntity.LawStatus.VOTING);
		String pseudonym = pseudonym("checksumLevelTestHash");

		BallotEntity votedDirectly = new BallotEntity(poll, 0, List.of(propA, propB), pseudonym);
		votedDirectly.calcSha256Checksum();
		BallotEntity castByProxy = new BallotEntity(poll, 2, List.of(propA, propB), pseudonym);
		castByProxy.calcSha256Checksum();

		assertEquals(votedDirectly.checksum, castByProxy.checksum,
				"level must not be part of the checksum input");
	}

	@Test
	@DisplayName("Concatenating poll.id and the vote-order ids without a delimiter must not collide")
	public void checksumDoesNotCollideOnAdjacentDigits() {
		// This is the precise case a "|"-separated canonical form exists to rule out. Without a
		// delimiter between poll.id and the joined proposal ids, "1" followed by "23" is the exact
		// same character sequence as "12" followed by "3" - two DIFFERENT (poll, voteOrder) pairs
		// would land on the identical input string, and therefore the identical checksum.
		ProposalEntity singleProposalId23 = proposalWithId(23, ProposalEntity.LawStatus.VOTING);
		ProposalEntity singleProposalId3 = proposalWithId(3, ProposalEntity.LawStatus.VOTING);
		String pseudonym = pseudonym("checksumAdjacentDigitsTestHash");

		BallotEntity pollOneVoteTwentyThree = new BallotEntity(pollWithId(1), 0, List.of(singleProposalId23), pseudonym);
		pollOneVoteTwentyThree.calcSha256Checksum();
		BallotEntity pollTwelveVoteThree = new BallotEntity(pollWithId(12), 0, List.of(singleProposalId3), pseudonym);
		pollTwelveVoteThree.calcSha256Checksum();

		assertNotEquals(pollOneVoteTwentyThree.checksum, pollTwelveVoteThree.checksum,
				"poll.id=1 with voteOrder=[23] and poll.id=12 with voteOrder=[3] must NOT produce the " +
				"same checksum - without a delimiter between them, both concatenate to \"123\"");
	}
}
