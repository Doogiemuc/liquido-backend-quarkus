package org.liquido.polly;

import org.eclipse.microprofile.graphql.Name;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The one shape every polly operation returns.
 *
 * <h3>Why a DTO rather than the entity</h3>
 * The rest of this codebase returns entities straight out of GraphQL. Polly does not, for two
 * reasons that both matter here:
 * <ol>
 *   <li>{@code ownerKey} and the numeric primary key are exactly the fields that must never be
 *       serialised - ownership is the only thing protecting Edit and Finish, and a sequential
 *       id would turn the share link into no access control at all. A DTO makes leaking them
 *       impossible instead of depending on somebody remembering an {@code @Ignore}.</li>
 *   <li>{@code numBallots}, {@code isOwner} and {@code alreadyVoted} are <b>per caller</b>.
 *       Building this fresh on every request is inherently correct; handing back a cached
 *       polly-shaped object without recomputing them showed the wrong buttons to the wrong
 *       people once already.</li>
 * </ol>
 *
 * <p>Plain public fields with no Lombok getters, on purpose. A generated {@code isOwner()}
 * would make MicroProfile GraphQL publish the field as {@code owner}, and the frontend query
 * asks for {@code isOwner} - hence the explicit {@link Name} annotations too.
 */
public class PollyResponse {

	/** The opaque id from the share link. Never the database key. */
	public String publicId;

	public String title;

	public PollyStatus status;

	public LocalDateTime createdAt;

	/** Set when the owner finished the polly; null while it is running. */
	public LocalDateTime votingEndAt;

	/**
	 * How many ballots have been cast. Shown to everyone, not just the owner - the frontend
	 * reads it as a non-owner too, and displays it to all once the polly is finished.
	 */
	public int numBallots;

	/** Does the caller own this polly? Drives Edit and Finish. False without a session. */
	@Name("isOwner")
	public boolean isOwner;

	/** Has the caller already voted? Drives ballot vs "you already voted". False without a session. */
	@Name("alreadyVoted")
	public boolean alreadyVoted;

	/** The options, in the order the creator typed them. */
	public List<Proposal> proposals;

	/** Null until FINISHED, and still null after that if nobody voted. */
	public Proposal winner;

	/**
	 * One option.
	 * <p>The id is a {@code String}: the frontend sends {@code voteOrder: [ID!]!} as strings and
	 * compares {@code proposal.id === polly.winner.id}, so a numeric type here would fail
	 * strict equality on the client.
	 */
	public record Proposal(String id, String title) {
		static Proposal of(PollyProposalEntity entity) {
			return entity == null ? null : new Proposal(String.valueOf(entity.id), entity.title);
		}
	}

	/**
	 * Shape a polly for whoever is asking.
	 *
	 * @param polly the polly
	 * @param numBallots ballots cast so far
	 * @param ownerKey the caller's owner key, or null when they have no session
	 * @param voterKey the caller's voter key <i>for this polly</i>, or null when they have no session
	 */
	public static PollyResponse of(PollyEntity polly, long numBallots, String ownerKey, String voterKey) {
		PollyResponse res = new PollyResponse();
		res.publicId = polly.publicId;
		res.title = polly.title;
		res.status = polly.status;
		res.createdAt = polly.createdAt;
		res.votingEndAt = polly.votingEndAt;
		res.numBallots = (int) numBallots;
		res.isOwner = ownerKey != null && ownerKey.equals(polly.ownerKey);
		res.alreadyVoted = voterKey != null && PollyBallotEntity.hasVoted(polly, voterKey);
		res.proposals = polly.proposals.stream().map(Proposal::of).toList();
		res.winner = Proposal.of(polly.winner);
		return res;
	}
}
