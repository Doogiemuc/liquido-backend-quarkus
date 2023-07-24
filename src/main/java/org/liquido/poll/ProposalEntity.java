package org.liquido.poll;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.smallrye.common.constraint.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.liquido.model.BaseEntity;
import org.liquido.user.UserEntity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor(force = true)      // Lombok's Data does NOT include a default no args constructor!
@RequiredArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Entity(name = "proposals")
@JsonIgnoreProperties(ignoreUnknown = true)  // ignore eg. isLikedByCurrentUser when deserializing
public class ProposalEntity extends BaseEntity {

	//TODO: Add a Proposal.UUID   Clients shouldn't use our DB internal ID in castVoteRequests

	/**
	 * Title of a proposal.
	 */
	@NotNull   // title must really not be null :-)
	@lombok.NonNull
	//@Column(unique = true)  //Title is not unique throughout ALL proposals in the DB. But MUST be unique within the poll! This is checked in PollService.addProposal. OK!
	public String title;

	/**
	 * HTML description of this proposal. This description can only be edited by the creator
	 * as long as the proposal is not yet in a poll in voting phase.
	 */
	@NotNull
	@NonNull
	@Column(length = 1000)
	@Size(min=20, message = "Proposal description must be at least some characters long.")
	public String description;

	/** A nice looking icon. Mobile clients stores fontawesome icon names here. */
	@Nullable
	public String icon = null;


	/** enumeration for proposal status */
	public enum LawStatus {
		IDEA(0),            // An idea is a newly created proposal for a proposal that did not reach its quorum yet.
		PROPOSAL(1),        // When an idea reaches its quorum, then it becomes a proposal and can join a poll.
		ELABORATION(2),     // Proposal is part of a poll and can be discussed. Voting has not yet started.
		VOTING(3),          // When the voting phase starts, the description of a proposals cannot be changed anymore.
		LAW(4),             // The winning proposal becomes a proposal.
		LOST(5),         		// All non winning proposals in a finished poll are dropped. They lost the vote.
		RETENTION(6),       // When a proposal looses support, it is in the retention phase
		RETRACTED(7);       // When a proposal looses support for too long, it will be retracted.
		final int statusId;
		LawStatus(int id) { this.statusId = id; }
	}

	/** Current status: idea, proposal, in elaboratin, in voting, law, ... */
	@NotNull
	@NonNull
	public LawStatus status =  LawStatus.IDEA;

	/**
	 * Users that support this.
	 * This is not just a counter, because there are restrictions:
	 *  - A user must not support an idea, proposal or law more than once.
	 *  - A user must not support his own idea, proposal or law.
	 *  - When a new support is added, this idea might become a proposal.
	 *
	 * This attribute is private, so that you cannot (and must not) call idea.getSupporters.add(someUser)
	 * Instead you must use LawService.addSupporter()   or POST to /laws/{id}/like
	 */
	@JsonIgnore  // do not serialize when returning JSON. Only return this.getNumSupporters()
	@ManyToMany(fetch = FetchType.EAGER)
	@JoinTable(name = "proposal_supporters")
	private Set<UserEntity> supporters = new HashSet<>();

	/**
	 * When in status ELABORATION this is the link to the poll.
	 * All alternative proposals point to the same poll.
	 * Can be NULL, when this is still an idea or proposal!
	 * This is the many side of a bidirectional ManyToOne aggregation relationship.
	 * https://vladmihalcea.com/2017/03/29/the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate/
	 *
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	//@JoinColumn(name="poll_id")  this column name is already the default
	@JsonBackReference      // necessary to prevent endless cycle when (de)serializing to/from JSON: http://stackoverflow.com/questions/20218568/direct-self-reference-leading-to-cycle-exception
	public PollEntity poll = null;

	/** Comments and suggestions for improvement for this proposal
	@JsonIgnore  																											// To fetch comments via REST user CommentProjection of CommentModel
	@OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)  	//TODO: We should defenitely change this to FetchTypoe.EAGER for performance reasons. Check with tests if FetchType.LAZY is ok
	//@Cascade(org.hibernate.annotations.CascadeType.ALL)   					// https://vladmihalcea.com/a-beginners-guide-to-jpa-and-hibernate-cascade-types/
	public Set<CommentModel> comments = new HashSet<>();							// Comments are deliberately a Set and not a List. There are no duplicates.

	 //TODO: Comments
	*/

	/**
	 * Date when this proposal reached its quorum.
	 * Will be set, when enough likes are added.
	 */
	LocalDateTime reachedQuorumAt;



	//MAYBE: lawModel.tags or related ideas? => relations built automatically, when a proposal is added to a running poll.



	//Remember: You MUST NOT call idea.getSupporters.add(someUser) directly! Because this circumvents functional restrictions.
	//E.g. a user must not support his own idea. Call LawService.addSupporter() instead!
	public int getNumSupporters() {
		if (this.supporters == null) return 0;
		return this.supporters.size();
	}

	/*
	private static int getNumChildComments(CommentModel c) {
		int count = 0;
		if (c == null || c.getReplies() == null || c.getReplies().size() == 0) return 0;
		for (CommentModel reply : c.getReplies()) {
			count += 1 + getNumChildComments(reply);
		}
		return count;
	}

	/*
	 * Number of comments and (recursive) replies.
	 * @return overall number of comments

	@JsonIgnore  // prevent LazyInitialisationException for getter - comments might not be loaded
	public int getNumComments() {
		if (this.comments == null || comments.size() == 0) return 0;
		int count = 0;
		for (CommentModel c : comments) {
			count += 1 + getNumChildComments(c);
		}
		//Optional<Integer> countStream = comments.stream().map(LawModel::getNumChildComments).reduce(Integer::sum);
		return count;
	}

	 */

	/**
	 * Description can only be changed when in status IDEA or PROPOSAL
	 * @param description the new description (HTML)
	 */
	public void setDescription(String description) {
		if (LawStatus.IDEA.equals(this.getStatus()) ||
				LawStatus.PROPOSAL.equals(this.getStatus()) ||
				LawStatus.ELABORATION.equals(this.getStatus())
		) {
			this.description = description;
		} else {
			throw new RuntimeException("Must not change description in status "+this.getStatus());
		}
	}

	/** Stringify mostly all info about this idea, proposal or law */
	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append("Proposal[");
		buf.append("id=" + this.id);
		buf.append(", title='" + title + "'");
		buf.append(", description='");
		if (description != null && description.length() > 100) {
			buf.append(description.substring(0, 100));
			buf.append("...");
		} else {
			buf.append(description);
		}
		buf.append('\'');
		buf.append(", poll.id=" + (poll != null ? poll.id : "<null>"));
		buf.append(", status=" + status);
		buf.append(", numSupporters=" + getNumSupporters());
		//TODO: buf.append(", numComments=" + getNumComments());					// keep in mind  that comments are loaded lazily. So toString MUST be called within a Hibernate Session
		//TODO: buf.append(", createdBy.email=" + (createdBy != null ? createdBy.getEmail() : "<null>"));
		buf.append(", reachedQuorumAt=" + reachedQuorumAt);
		buf.append(", updatedAt=" + updatedAt);
		buf.append(", createdAt=" + createdAt);
		buf.append(']');
		return buf.toString();
	}

	/** Nice and short representation of an Idea, Proposal or Law as a string */
	public String toStringShort() {
		StringBuilder buf = new StringBuilder();
		buf.append("Proposal[");
		buf.append("id=" + id);
		buf.append(", title='" + title + "'");
		if (poll != null) buf.append(", poll.id=" + poll.id);
		buf.append(", status=" + status);
		//TODO: buf.append(", createdBy.email=" + (createdBy != null ? createdBy.getEmail() : "<null>"));
		buf.append(']');
		return buf.toString();
	}
}