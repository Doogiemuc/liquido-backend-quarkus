package org.liquido.vote;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * POJO Entity that represents an anonymous vote that a user has casted for one given poll.
 *
 * Each ballot contains the ordered list of proposals that this user voted for.
 * But the ballot does *NOT* contain any reference to the voter.
 * Instead, each ballot contains a checksum which is the hashed value of the user's voterToken.
 *
 * Only the voter knows his own voterToken. So only he can check that this actually is his ballot.
 * This way a voter can even update his ballot as long as the voting phase is still open.
 */
@Data
@Entity(name = "ballots")
@NoArgsConstructor(force = true)
@RequiredArgsConstructor                      //BUGFIX: https://jira.spring.io/browse/DATAREST-884
@EqualsAndHashCode(callSuper = true)
//@Table(uniqueConstraints = {
//		@UniqueConstraint(columnNames = {"poll_id", "hashedVoterInfo"})   // a voter is only allowed to vote once per poll with his hashedVoterToken!
//})
public class BallotEntity extends PanacheEntity {
	//BallotModel deliberately does NOT extend BaseEntity!
	//No @CreatedDate, No @LastModifiedDate! This could lead to timing attacks.  <=== maybe reconsider? Should I have a CreatedDate on Ballots?
	//No @CreatedBy ! When voting it is confidential who did cast this ballot and when.

	/**
	 * Reference to the poll this ballot was cast in.
	 */
	@NotNull
	@NonNull
	@ManyToOne(fetch = FetchType.LAZY)
	@JsonBackReference
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
	//@JoinColumn(name = "hashedVoterInfo")    // The @Id of a RightToVoteModel is the hashedVoterToken itself
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
	 */
	@PostUpdate
	@PrePersist
	public void calcSha256Checksum() {
		this.checksum = DigestUtils.sha3_256Hex(
				// Cannot include this.ID in checksum. It's not present when saving a new Ballot!
				this.getVoteOrder().hashCode() +
						this.getPoll().hashCode() +
						this.getRightToVote().hashedVoterInfo);
	}


	public static Optional<BallotEntity> findByPollAndRightToVote(PollEntity poll, RightToVoteEntity rightToVote) {
		return BallotEntity.find("poll = ?1 and rightToVote = ?2", poll, rightToVote).firstResultOptional();
	}

	public static Optional<BallotEntity> findByPollAndChecksum(PollEntity poll, String checksum) {
		return BallotEntity.find("poll = ?1 and checksum = ?2", poll, checksum).firstResultOptional();
	}

	@Override
	public String toString() {
		String proposalIds = voteOrder.stream().map(law -> law.id.toString()).collect(Collectors.joining(","));
		return "Ballot[" +
				"id=" + id +
				", poll(id=" + poll.id +
				", status=" + poll.getStatus() + ")" +
				", level=" + level +
				", voteOrder(proposalIds)=[" + proposalIds + "]" +
				//Do not expose rightToVote!!! It might include public proxy name
				"]";
	}

}