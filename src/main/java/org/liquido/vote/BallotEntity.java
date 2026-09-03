package org.liquido.vote;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.graphql.Ignore;

/**
 * POJO Entity that represents an anonymous vote that a user has casted for one given poll.
 *
 * Each ballot contains the ordered list of proposals that this user voted for.
 * But the ballot does *NOT* contain any reference to the voter.
 * Instead, each ballot contains a checksum which is the hashed value of the user's voterToken.
 *
 * Only the voter knows his own voterToken. So only he can check that this actually is his ballot.
 *
 * A voter's own direct (level 0) vote, once cast, cannot be changed - see
 * {@link org.liquido.vote.CastVoteService#castVoteRec}. A proxy's ballot MAY still be overridden by
 * a closer proxy or by the delegee's own direct vote; that is a different mechanism (delegation
 * resolution), not the voter changing their mind.
 */
@Data
@Entity(name = "ballots")
@NoArgsConstructor(force = true)
@RequiredArgsConstructor                      //BUGFIX: https://jira.spring.io/browse/DATAREST-884
@EqualsAndHashCode(callSuper = true)
@Table(uniqueConstraints = {
		// ONE ballot per voter per poll, enforced by the DATABASE. The application check in
		// CastVoteService.castVoteRec() exists for a readable error message; this constraint is the
		// authority, because a read-then-insert has a race window between the read and the insert and
		// a constraint does not. Same reasoning (and same naming) as uq_polly_ballot_voter.
		//
		// This does NOT restrict a proxy: a proxy writes one ballot per delegee, and each delegee
		// derives their OWN pseudonym, so every row differs in ballot_pseudonym.
		@UniqueConstraint(name = "uq_ballot_poll_voter", columnNames = {"poll_id", "ballot_pseudonym"})
})
public class BallotEntity extends PanacheEntityBase {
	//BallotModel deliberately does NOT extend BaseEntity!
	//No @CreatedDate, No @LastModifiedDate! This could lead to timing attacks.
	//No @CreatedBy ! When voting it is confidential who did cast this ballot and when.

	/**
	 * Random (v4), never sequential.
	 *
	 * <p>Omitting the timestamps above achieves nothing while the primary key is an
	 * auto-incrementing number: the key then reveals the order ballots were inserted in just as
	 * effectively as a creation date reveals the time, and unlike the date it is exposed through
	 * the API. Correlating that order against who was seen online defeats the same anonymity the
	 * missing timestamps exist to protect.
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	/**
	 * Reference to the poll this ballot was cast in.
	 */
	@NotNull
	@NonNull
	@ManyToOne(fetch = FetchType.LAZY) // Changed from LAZY to EAGER
	@JsonBackReference
	@Ignore  //SECURITY IMPORTANT: ignore in GraphQL and JSON. @JsonBackReference alone does NOT hide a field
	         // from the GraphQL schema (unlike @JsonIgnore, which SmallRye GraphQL does honour). Without
	         // this, the unauthenticated verifyBallot() reached ballot->poll->team->inviteCode/members.
	public PollEntity poll;

	/**
	 * level = 0: user voted for himself
	 * level = 1: direct proxy
	 * level = 2: transitive proxy voted
	 * etc. */
	@NonNull   // level must be set in RequiredArgsConstructor
	@NotNull
	public Integer level;

	/**
	 * A voter sorts some proposals of this poll into his personally preferred order.
	 * A voter may put some or all proposals of the poll into his (ordered) ballot.
	 * But of course every proposal may appear only once in his voteOrder!
	 * And one proposal may be voted for by several voters => ManyToMany relationship
	 */
	//BE CAREFUL: Lists are not easy to handle in Hibernate: https://vladmihalcea.com/hibernate-facts-favoring-sets-vs-bags/
	//In Quarkus GraphQL this is serialized as a list of objects with id attribute, eg. [{id:4711},{id:4712},{id:4713}]
	@NonNull
	@NotNull
	@ManyToMany(fetch = FetchType.EAGER)   // (cascade = CascadeType.MERGE, orphanRemoval = false)
	@JoinTable(name = "ballot_voteOrder")
	@OrderColumn(name = "proposal_order")    // keep order in DB
	//TODO: do I need a uniqueConstraint so that proposalModel.id can only appear once in voteOrder?
	public List<ProposalEntity> voteOrder;


	public void setVoteOrder(List<ProposalEntity> voteOrder) {
		if (voteOrder == null || voteOrder.isEmpty())
			throw new IllegalArgumentException("Vote Order must not be null or empty!");
		this.voteOrder = voteOrder;
	}

	/**
	 * The POLL-SCOPED pseudonym this ballot was cast under: HMAC(secret, hashedVoterInfo | poll.id).
	 *
	 * <p>A ballot deliberately holds <b>no reference to the right to vote</b> and no foreign key to
	 * it. A direct link would mean one voter's ballots across ten polls all pointed at the same row,
	 * so anyone with the database could group them into a voting history without ever breaking a
	 * hash. Deriving per poll instead makes those ten ballots carry ten unrelated values.
	 *
	 * <p>The mapping from a right to vote to this value is never persisted anywhere. The server
	 * re-derives it on demand when a voter asks about their own ballot, because it holds the secret.
	 *
	 * <p>If a proxy casts a vote for a delegee, this is still the DELEGEE's pseudonym, derived from
	 * the delegee's own right to vote -- so it remains their ballot.
	 */
	@NotNull
	@NonNull
	@Column(name = "ballot_pseudonym", nullable = false, length = 64)
	@JsonIgnore   // [SECURITY] never expose the pseudonym: with the server secret it links back to a voter
	@Ignore
	public String ballotPseudonym;

	/**
	 * The checksum of a ballot uniquely identifies this ballot.
	 * The checksum is calculated from the poll, the ordered proposal ids and the ballotPseudonym.
	 * It deliberately does not depend on the delegation level.
	 */
	public String checksum;

	/**
	 * This automatically calculates the checksum when the ballot is saved.
	 *
	 * <p><b>Must run on @PrePersist / @PreUpdate, not @PostUpdate.</b> A @PostUpdate callback fires
	 * AFTER the flush, so a checksum computed there is not guaranteed to land in the same
	 * transaction as the change that triggered it: after a voter changed their ranking, the stored
	 * checksum could silently stop matching the stored voteOrder.
	 *
	 * <p><b>Built from database IDs, never from hashCode().</b> {@link ProposalEntity} is
	 * {@code @EqualsAndHashCode(of={"title","status"})}, and {@code PollService.finishVotingPhase()}
	 * sets every proposal's status to LOST or LAW. From that moment on, the inputs that produced the
	 * ORIGINAL checksum no longer exist in the form they had at signing time -- so neither the voter
	 * nor an auditor could ever recompute it again, which defeats the whole point of a checksum.
	 * Database IDs are immutable for the life of the row, unlike a proposal's status.
	 *
	 * <p><b>Explicit separators, not arithmetic string-plus-int concatenation.</b> The previous
	 * version built {@code voteOrder.hashCode() + poll.hashCode() + rightToVote.hashedVoterInfo}.
	 * Java evaluates {@code int + int} arithmetically before the result is concatenated with the
	 * following String, so two independent 32-bit values collapsed into one sum with no separation
	 * from what came after -- there was no domain separation between "which ranking" and "which
	 * poll" at all. The "v2|"-prefixed, "|"-joined form below fixes that and, since it is versioned,
	 * lets a future change to this canonical form coexist with this one without ambiguity.
	 */
	@PrePersist
	@PreUpdate
	public void calcSha256Checksum() {
		String voteOrderIds = this.voteOrder.stream()
				.map(proposal -> String.valueOf(proposal.id))
				.collect(Collectors.joining(","));
		String canonical = "v2|" + this.poll.id + "|" + voteOrderIds + "|" + this.ballotPseudonym;
		this.checksum = DigestUtils.sha3_256Hex(canonical);
	}


	public static Optional<BallotEntity> findByPollAndPseudonym(PollEntity poll, String ballotPseudonym) {
		return BallotEntity.find("poll = ?1 and ballotPseudonym = ?2", poll, ballotPseudonym).firstResultOptional();
	}

	public static Optional<BallotEntity> findByPollAndChecksum(PollEntity poll, String checksum) {
		return BallotEntity.find("poll = ?1 and checksum = ?2", poll, checksum).firstResultOptional();
	}

	@Override
	public String toString() {
		String proposalIds = voteOrder.stream().map(prop -> prop.id.toString()).collect(Collectors.joining(","));
		StringBuilder sb = new StringBuilder();
		sb.append("Ballot [");
		sb.append("id=");
		sb.append(id);
		if (poll != null) {
			sb.append(", poll.id=");
			sb.append(poll.id);
		} else {
			sb.append(", poll=<null>");
		}
		sb.append(", level=");
		sb.append(level);
		sb.append(", voteOrder(proposalIds)=[");
		sb.append(proposalIds);
		sb.append("], checksum=");
		sb.append(checksum);
		sb.append("]");
		return sb.toString();
	}

}