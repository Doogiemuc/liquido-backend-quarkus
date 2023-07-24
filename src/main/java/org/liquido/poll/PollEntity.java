package org.liquido.poll;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.liquido.model.BaseEntity;
import org.liquido.poll.converter.MatrixConverter;
import org.liquido.team.TeamEntity;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.Matrix;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * This Team entity is the data model of a team in the backend database.
 * See UserGraphQL for the representation of a Team in the GraphQL API.
 */
@Data
@NoArgsConstructor(force = true)                              // Lombok's Data does NOT include a default no args constructor!
@RequiredArgsConstructor                        // And then does not create a required args constructor :-(  https://stackoverflow.com/questions/37671467/lombok-requiredargsconstructor-is-not-working
@EqualsAndHashCode(of={}, callSuper = true)    	// Compare teams by their Id only. teamName may change.
@Entity(name = "polls")
public class PollEntity extends BaseEntity {

	/**
	 * The title of a poll must be unique within the team.
	 * It can be edited by anyone who has a proposal in this poll.
	 */
	@NotNull
	@lombok.NonNull
	String title;

	@ManyToOne(fetch = FetchType.LAZY)
	@JsonBackReference
	TeamEntity team;

	/**
	 * The set of proposals in this poll. All of these proposal must already have reached their quorum.
	 * There cannot be any duplicates. A proposal can join a poll only once.
	 * When the poll is in PollStatus == ELABORATION, then these proposals may still be changed and further
	 * proposals may be added. When The PollStatus == VOTING, then proposals must not be added or be changed anymore.
	 */
  /* Implementation notes:
     This is the ONE side of a bidirectional ManyToOne aggregation relationship.
     Keep in mind that you must not call  poll.proposals.add(prop). Because this circumvents all the restrictions that there are for adding a proposals to a poll!
     Instead use PollService.addProposalToPoll(proposals, poll) !
	   We deliberately fetch all proposals in this poll EAGERly, so that getNumCompetingProposals can be called on the returned entity.
	   When creating a new poll via POST /polls/add  , then the first proposal can be passed as URI:   {"title":"Poll created by test 1582701066468","proposals":["/laws/405"]}
	   To make that work, the content of the HashSet, ie. the URI will be deserialized with LawModelDeserializer.class
	   https://vladmihalcea.com/the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate/            <== BEST BLOG POST ABOUT THIS TOPIC!!!
	   https://vladmihalcea.com/2017/03/29/the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate/
		 Beginners guide to Hibernate Cascade types:  https://vladmihalcea.com/2015/03/05/a-beginners-guide-to-jpa-and-hibernate-cascade-types/

		 Do not simply add to this Set. Instead, use PollService.addProposal(...)
	*/
	@OneToMany(cascade = CascadeType.ALL, mappedBy="poll", fetch = FetchType.EAGER) //, orphanRemoval = true/false ?? Should a proposals be removed when the poll is deleted? => NO. Liquido Proposals may join other polls ...
	Set<ProposalEntity> proposals = new HashSet<>();

	// Some older notes, when proposals still was a SortedSet.  Not relevant anymore, but still very interesting reads!
	// I had problems with ArrayList: https://stackoverflow.com/questions/1995080/hibernate-criteria-returns-children-multiple-times-with-fetchtype-eager
	// So I used a SortedSet:   https://docs.jboss.org/hibernate/orm/5.2/userguide/html_single/Hibernate_User_Guide.html#collections-sorted-set   => Therefore LawModel must implement Comparable
	// See also https://vladmihalcea.com/hibernate-facts-favoring-sets-vs-bags/
	// @SortNatural		// sort proposals in this poll by their ID  (LawModel implements Comparable)

	public enum PollStatus {
		ELABORATION(0),     // When the initial proposal reaches its quorum, the poll is created. Alternative proposals can be added in this phase.
		VOTING(1),          // When the voting phase starts, all proposals can be voted upon. No more alternative proposals can be added. Proposals cannot be edited in this phase.
		FINISHED(2);        // The winning proposal becomes a proposal.
		int statusId;
		PollStatus(int id) { this.statusId = id; }
	}

	/** initially a poll is in its elaboration phase, where further proposals can be added */
	PollStatus status = PollStatus.ELABORATION;

	/** Date when the voting phase started. Will be set in PollService */
	LocalDateTime votingStartAt = null;

	/** Date when the voting phase will end. Will be set in PollService */
	LocalDateTime votingEndAt = null;

	/** The wining proposal of this poll, that became a proposal. Filled after poll is FINISHED. */
	@OneToOne(cascade = CascadeType.PERSIST)
	ProposalEntity winner = null;

	/**
	 * The calculated duelMatrix when the voting phase is finished.
	 * This attribute is serialized as JSON array of arrays and then stored as VARCHAR
	 */
	@Convert(converter = MatrixConverter.class)
	Matrix duelMatrix = null;


	//Implementation note: A poll does not contain a link to its BallotModels. We do not want to expose the ballots while the voting phase is still running.
	// But clients can get the number of already casted ballots.
	public long getNumBallots() {
		return BallotEntity.count("poll", this);
	}

	/** return the number of competing proposals */
	public int getNumCompetingProposals() {
		if (proposals == null) return 0;
		return proposals.size();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder()
				.append("Poll[")
				.append("id=").append(id)
				.append(", status=").append(status)
				.append(", title='").append(title).append("'")
				.append(", team.id=").append(team != null ? team.id : "null")
				.append(", numProposals=").append(proposals != null ? proposals.size() : "0")
				//.append(", createdBy=").append(this.createdBy.getEmail())
				.append(']');
		return sb.toString();
	}


}