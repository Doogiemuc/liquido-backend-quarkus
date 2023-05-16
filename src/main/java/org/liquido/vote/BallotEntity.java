package org.liquido.vote;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import lombok.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
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
@Table(uniqueConstraints = {
		@UniqueConstraint(columnNames = {"POLL_ID", "hashedVoterToken"})   // a voter is only allowed to vote once per poll with his hashedVoterToken!
})
public class BallotEntity extends PanacheEntity {
	//BallotModel deliberately does NOT extend BaseEntity!
	//No @CreatedDate, No @LastModifiedDate! This could lead to timing attacks.
	//No @CreatedBy ! When voting it is confidential who did cast this ballot and when.

	/**
	 * Reference to the poll this ballot was cast in.
	 */
	@NotNull
	@NonNull
	@ManyToOne(fetch = FetchType.LAZY)
	//@JsonProperty("_links")                        // JSON will contain "_links.poll.href"
	//@JsonSerialize(using = PollAsLinkJsonSerializer.class)
	@JsonBackReference
	public PollEntity poll;

	/**
	 * Get current status of poll that this ballot was casted in.
	 * This will expose the PollStatus in the JSON response.
	 * If the poll is still in its voting phase (poll.status == VOTING),
	 * then the ballot can still be changed by the voter.
	 * @return PollStatus, e.g. VOTING or FINISHED
	 */
	public PollEntity.PollStatus getPollStatus() {
		return this.poll != null ? poll.getStatus() : null;
	}

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
	//BE CAREFULL: Lists are not easy to handle in hibernate: https://vladmihalcea.com/hibernate-facts-favoring-sets-vs-bags/
	@NonNull
	@NotNull
	@ManyToMany(fetch = FetchType.EAGER)   // (cascade = CascadeType.MERGE, orphanRemoval = false)
	@JoinTable(name = "ballot_voteOrder")
	@OrderColumn(name = "proposal_order")    // keep order in DB
	//TODO: do I need a uniqueConstraint so that proposalModel.id can only appear once in voteOrder?
	public List<ProposalEntity> voteOrder;


	public void setVoteOrder(List<ProposalEntity> voteOrder) {
		if (voteOrder == null || voteOrder.size() == 0)
			throw new IllegalArgumentException("Vote Order must not be null or empty!");
		this.voteOrder = voteOrder;
	}

	/**
	 * Encrypted and anonymous information about the voter that casted this vote into the ballot.
	 * Only the voter knows the voterToken that this checksumModel was created from as
	 *   rightToVote.hashedVoterToken = hash(voterToken)
	 * If a vote was cast by a proxy, this is still the voters (delegated) checksum.
	 */
	@NotNull
	@NonNull
	@OneToOne
	@JoinColumn(name = "hashedVoterToken")    // The @Id of a RightToVoteModel is the hashedVoterToken itself
	@JsonIgnore                                // [SECURITY] Do not expose voter's private right to vote (which might also include public proxies name)
	public RightToVoteEntity rightToVote;

	/**
	 * The MD5 checksum of a ballot uniquely identifies this ballot.
	 * The checksum is calculated from the voteOrder, poll.hashCode and rightToVote.hashedVoterToken.
	 * It deliberately does not depend on level or rightToVote.delegatedTo !
	 */
	public String checksum;

	@PreUpdate
	@PrePersist
	public void calcMD5Checksum() {
		this.checksum = DigestUtils.md5Hex(
				// Cannot include ID. Its not not present when saving a new Ballot!
				this.getVoteOrder().hashCode() +
						this.getPoll().hashCode() +
						this.getRightToVote().hashedVoterToken);
	}

	public static Optional<BallotEntity> findByPollAndRightToVote(PollEntity poll, RightToVoteEntity rightToVote) {
		return BallotEntity.find("poll = ?1 and rightToVote = ?2", poll, rightToVote).firstResultOptional();
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