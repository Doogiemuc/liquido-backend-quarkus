package org.liquido.poll;

import org.eclipse.microprofile.graphql.Description;

import java.util.List;

/**
 * Everything a third party needs to recompute a finished poll's result for themselves.
 *
 * <h2>What universal verifiability means here</h2>
 *
 * A voter can already confirm their OWN ballot, by looking up its checksum. That is individual
 * verifiability, and on its own it proves nothing about the announced winner: a server that counted
 * honestly and a server that dropped half the ballots look identical to a voter who can only see
 * their own. Universal verifiability is the missing half -- anyone can check that the published
 * result actually follows from the published ballots.
 *
 * <p>Ranked Pairs is deterministic, so this needs no cryptography, only complete inputs:
 *
 * <ol>
 *   <li>{@link #proposalOrder} -- the axes of the duel matrix, ascending by id. Without it the
 *       matrix is a grid of numbers with no stated meaning, and cannot be checked at all.</li>
 *   <li>{@link #ballots} -- every ballot's ranking, as proposal ids, with its checksum.</li>
 *   <li>{@link #duelMatrix} and {@link #winnerId} -- what the server says it computed.</li>
 * </ol>
 *
 * An auditor recomputes the matrix from the ballots and re-runs Ranked Pairs. A voter additionally
 * finds their own checksum in the list, which is what turns "my ballot was counted" into a spot
 * check on the whole count.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <b>The ballot pseudonym.</b> It is the value that, with the server secret, leads back to a voter.
 * Publishing it would hand an operator-equivalent adversary the entire electorate.
 *
 * <b>The delegation level.</b> It is irrelevant to the count -- every ballot counts once whatever
 * cast it -- and publishing it would expose how much of a poll was decided by proxies and how deep
 * the chains ran.
 *
 * <h2>The cost, stated rather than discovered</h2>
 *
 * Publishing full rankings enables the <b>Italian attack</b>: with enough proposals, a distinctive
 * ranking is effectively a signature, so a coercer can demand a specific unusual ordering in advance
 * and then look for it in the published set. The risk grows with the number of proposals and is
 * negligible for two or three.
 *
 * <p>LIQUIDO already states that it is not receipt-free (whitepaper 5.2): the checksum receipt is
 * transferable by construction, so a determined coercer has a simpler route already. This therefore
 * adds verifiability without introducing a class of attack the system claimed to resist -- but it is
 * an accepted trade, made deliberately, not a side effect. In a setting where coercion is the
 * dominant threat, publishing the tally is the wrong call and this endpoint should stay closed.
 */
@Description("Everything needed to independently recompute a finished poll's result")
public class PublishedTally {

	@Description("The poll this tally belongs to")
	public Long pollId;

	@Description("Title of the poll, as it was when voting closed")
	public String pollTitle;

	/**
	 * The row and column axes of {@link #duelMatrix}, ascending by proposal id.
	 * Row i and column i of the matrix are this list's element i.
	 */
	@Description("Proposal ids in the order that indexes the duel matrix rows and columns, ascending")
	public List<Long> proposalOrder;

	/**
	 * Published as a nested list rather than as the internal {@link Matrix}. {@code Matrix} wraps a
	 * {@code long[][]}, and SmallRye GraphQL cannot serialize a nested Java array -- it flattens the
	 * declared type to {@code [BigInteger]} and then fails at runtime on every row. A matrix that
	 * cannot be fetched cannot be audited, which would defeat the point of publishing one.
	 */
	@Description("Pairwise comparison counts: entry [i][j] is how many ballots ranked proposal i above proposal j")
	public List<List<Long>> duelMatrix;

	@Description("The proposal the server announced as the winner, or null if the poll had no winner")
	public Long winnerId;

	@Description("How many ballots were counted")
	public int numBallots;

	@Description("Every ballot that was counted, anonymised: its checksum and its ranking. No pseudonym, no level.")
	public List<PublishedBallot> ballots;

	/** One counted ballot, stripped of everything that could lead back to a voter. */
	@Description("One counted ballot: the receipt a voter can recognise, and the ranking it carried")
	public static class PublishedBallot {

		@Description("The ballot's checksum -- the voter who cast it can recognise their own here")
		public String checksum;

		@Description("Proposal ids in the voter's preferred order, favourite first. May be partial.")
		public List<Long> voteOrder;

		public PublishedBallot() {}

		public PublishedBallot(String checksum, List<Long> voteOrder) {
			this.checksum = checksum;
			this.voteOrder = voteOrder;
		}
	}
}
