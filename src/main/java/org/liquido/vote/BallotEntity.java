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
		// This does NOT restrict a proxy: a proxy writes one ballot per delegee, and each delegee has
		// their own RightToVote, so every row differs in hashedVoterInfo.
		@UniqueConstraint(name = "uq_ballot_poll_voter", columnNames = {"poll_id", "hashedVoterInfo"})
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
	 * Link to the right to vote that this ballot was cast with.
	 * This cannot be traced back to the actual voter that did cast the vote.
	 * If a proxy casts a vote for a voter, then this still is the voter's ballot. It links to the voter's delegated rightToVote.
	 */
	@NotNull
	@NonNull
	@ManyToOne
	@JoinColumn(name = "hashedVoterInfo")    // The @Id of a RightToVoteModel is the hashedVoterToken itself. This also makes the name of the column in the DB more readable
	@JsonIgnore                               // [SECURITY] Do not expose voter's private right to vote (which might also include public proxies name)
	public RightToVoteEntity rightToVote;

	/**
	 * The checksum of a ballot uniquely identifies this ballot.
	 * The checksum is calculated from the voteOrder, poll.hashCode and rightToVote.hash.
	 * It deliberately does not depend on level or rightToVote.delegatedTo !
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
		// TODO(P2-2 in the security backlog): once a ballot stops storing a direct RightToVote
		// reference, this becomes the poll-scoped ballotPseudonym instead of hashedVoterInfo.
		String canonical = "v2|" + this.poll.id + "|" + voteOrderIds + "|" + this.rightToVote.hashedVoterInfo;
		this.checksum = DigestUtils.sha3_256Hex(canonical);
	}


	public static Optional<BallotEntity> findByPollAndRightToVote(PollEntity poll, RightToVoteEntity rightToVote) {
		return BallotEntity.find("poll = ?1 and rightToVote = ?2", poll, rightToVote).firstResultOptional();
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