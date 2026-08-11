package org.liquido.polly;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Polly: the small, fun sibling of a LIQUIDO poll.
 *
 * <p>No team, no account, no login screen. One opaque link that everybody opens, an identity
 * that is nothing but a passkey, and the same clever idea - you <i>sort</i> the options
 * instead of picking one.
 *
 * <h3>Why this is not a {@link org.liquido.poll.PollEntity}</h3>
 * Sharing the poll table was tried and reversed. It needed a nullable {@code team_id}, an
 * {@code isPolly} discriminator, a cache filter and two authorization regimes on one row -
 * and a polly still leaked into a team's poll list. Separate tables make that whole class of
 * bug impossible. The one thing the two products genuinely share is the Ranked Pairs winner
 * calculation in {@link org.liquido.vote.RankedPairVoting}.
 *
 * <h3>Ballot privacy</h3>
 * A polly ballot is <b>pseudonymous</b>, not anonymous: the server can link a passkey to its
 * ballot. That is the right trade for "where shall we go for dinner" and the wrong one for a
 * real election, which is why a team poll keeps its voterToken indirection. The UI says so
 * in both languages.
 */
@Data
@NoArgsConstructor(force = true)
@EqualsAndHashCode(of = {"publicId"}, callSuper = true)   // never include proposals: StackOverflowError in hashCode()
@Entity
@Table(name = "polly", indexes = {
		@Index(name = "idx_polly_public_id", columnList = "public_id", unique = true),
		@Index(name = "idx_polly_owner_key", columnList = "owner_key")
})
public class PollyEntity extends PanacheEntity {

	/**
	 * The opaque id in the share link, ~10 chars of base58.
	 *
	 * <p><b>Never expose the numeric primary key.</b> With a sequential id the share link
	 * would be the only access control, and {@code /polly/1,2,3...} would enumerate the title,
	 * options and results of every polly ever created.
	 */
	@NotNull
	@Column(name = "public_id", nullable = false, unique = true, length = 32)
	public String publicId;

	/** The question. */
	@NotNull
	@Column(nullable = false)
	public String title;

	/** A polly is live the instant it is created. */
	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	public PollyStatus status = PollyStatus.VOTING;

	/**
	 * {@code HMAC(secret, credentialId)} of the creator's passkey - stable for that credential
	 * across every polly, which is what makes {@code myPollys} work without an email address.
	 * <p>Must never reach the API: ownership is the only thing protecting Edit and Finish.
	 * That is enforced structurally by returning {@link PollyResponse} rather than this entity.
	 */
	@NotNull
	@Column(name = "owner_key", nullable = false, length = 64)
	public String ownerKey;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	public LocalDateTime createdAt;

	/** Set when the owner finishes the polly. Nothing expires a polly on its own. */
	@Column(name = "voting_end_at")
	public LocalDateTime votingEndAt;

	/** Filled by Ranked Pairs when the polly is finished. Stays null if nobody voted. */
	@OneToOne(cascade = CascadeType.PERSIST)
	@JoinColumn(name = "winner_id")
	public PollyProposalEntity winner;

	/**
	 * The options, in the order the creator typed them.
	 *
	 * <p>This is the only EAGER collection on a polly, on purpose. Ballots are deliberately
	 * <i>not</i> mapped here - a second eager List would mean {@code MultipleBagFetchException}
	 * (see the BUGFIX note on {@code TeamEntity.members}), and nothing ever wants a polly and
	 * all of its ballots in one go. Count them with
	 * {@code PollyBallotEntity.count("polly", polly)} instead.
	 */
	@OneToMany(mappedBy = "polly", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	@OrderBy("sortOrder ASC")
	public List<PollyProposalEntity> proposals = new ArrayList<>();

	public PollyEntity(String publicId, String title, String ownerKey) {
		this.publicId = publicId;
		this.title = title;
		this.ownerKey = ownerKey;
		this.status = PollyStatus.VOTING;
	}

	/** Replace the options wholesale. Only legal while no ballot exists - the service enforces that. */
	public void replaceProposals(List<String> titles) {
		this.proposals.clear();
		for (int i = 0; i < titles.size(); i++) {
			this.proposals.add(new PollyProposalEntity(this, titles.get(i), i));
		}
	}

	public boolean isFinished() {
		return PollyStatus.FINISHED.equals(this.status);
	}

	public static Optional<PollyEntity> findByPublicId(String publicId) {
		return find("publicId", publicId).firstResultOptional();
	}

	/** Every polly this passkey created, newest first. Replaces the "email me my link" step. */
	public static List<PollyEntity> findByOwnerKey(String ownerKey) {
		return list("ownerKey = ?1 order by createdAt desc", ownerKey);
	}

	@Override
	public String toString() {
		return "Polly[publicId=" + publicId + ", status=" + status
				+ ", numProposals=" + (proposals != null ? proposals.size() : 0) + "]";   // never log ownerKey or the title
	}
}
