package org.liquido.polly;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One cast vote in a polly: the options in the voter's preferred order, favourite first.
 *
 * <h3>The one-vote rule lives in the database</h3>
 * {@code UNIQUE (polly_id, voter_key)} is the single most important invariant of the product,
 * so it is a constraint rather than a check somebody has to remember in some code path.
 * {@code PollyService.vote()} does check first for a clean error message, but the constraint
 * is the authority - it is what holds under a double-tap or two concurrent requests.
 *
 * <p>An earlier design keyed the rule on <i>issuing a token</i> instead, which locked out
 * anyone who opened the page and wandered off without voting. The rule belongs on the ballot.
 *
 * <h3>voterKey, not credentialId</h3>
 * {@code voter_key = HMAC(secret, credentialId | pollyPublicId)}. Per-polly on purpose: the
 * same person is unlinkable <i>across</i> different pollys, and a stolen database alone
 * cannot link voters to ballots. The raw credential id is never stored on a ballot.
 * (The server itself still can link them - that is the accepted trade, see {@link PollyEntity}.)
 */
@Data
@NoArgsConstructor(force = true)
@EqualsAndHashCode(of = {"voterKey"}, callSuper = true)
@Entity
@Table(name = "polly_ballot", uniqueConstraints = {
		@UniqueConstraint(name = "uq_polly_ballot_voter", columnNames = {"polly_id", "voter_key"})
})
public class PollyBallotEntity extends PanacheEntity {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "polly_id", nullable = false)
	public PollyEntity polly;

	/** HMAC(secret, credentialId | polly.publicId). Never the raw credential id. */
	@Column(name = "voter_key", nullable = false, length = 64)
	public String voterKey;

	/**
	 * The options in the voter's preferred order, favourite first.
	 * <p>Same mapping as {@code BallotEntity.voteOrder}: a join table with an explicit order
	 * column, so Hibernate maintains the ordering rather than us sorting on read.
	 */
	@ManyToMany(fetch = FetchType.EAGER)
	@JoinTable(name = "polly_ballot_vote_order",
			joinColumns = @JoinColumn(name = "ballot_id"),
			inverseJoinColumns = @JoinColumn(name = "proposal_id"))
	@OrderColumn(name = "proposal_order")
	public List<PollyProposalEntity> voteOrder;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	public LocalDateTime createdAt;

	public PollyBallotEntity(PollyEntity polly, String voterKey, List<PollyProposalEntity> voteOrder) {
		this.polly = polly;
		this.voterKey = voterKey;
		this.voteOrder = voteOrder;
	}

	public static long countByPolly(PollyEntity polly) {
		return count("polly", polly);
	}

	public static List<PollyBallotEntity> findByPolly(PollyEntity polly) {
		return list("polly", polly);
	}

	public static boolean hasVoted(PollyEntity polly, String voterKey) {
		return count("polly = ?1 and voterKey = ?2", polly, voterKey) > 0;
	}

	@Override
	public String toString() {
		// Never log the voterKey: with it and the server secret, a log reader could link a passkey to a ballot.
		return "PollyBallot[id=" + id + ", polly=" + (polly != null ? polly.publicId : "<null>")
				+ ", numRanked=" + (voteOrder != null ? voteOrder.size() : 0) + "]";
	}
}
