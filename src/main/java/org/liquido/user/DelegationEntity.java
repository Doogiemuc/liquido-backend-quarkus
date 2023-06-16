package org.liquido.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import org.liquido.poll.BaseEntity;
import org.liquido.vote.RightToVoteEntity;

import javax.persistence.Entity;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Delegation from a user to a proxy in a given area.
 * A user can only have none or exactly one proxy per area.
 * One user may be the proxy for several "delegees" in one area.
 * A delegation is always implicitly created by fromUser, since a user may only choose proxy for himself.
 * This entity only consists of three foreign keys.
 */
@Entity(name = "delegations")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor(force = true)
@RequiredArgsConstructor  					//see also:  https://jira.spring.io/browse/DATAREST-884
@Table(uniqueConstraints= {
  @UniqueConstraint(columnNames = {"fromuser_id"})  // A user may only assign one proxy! //TODO: per area
})
//@IdClass(DelegationID.class)    //MAYBE: composite primary key.  But has issues with spring data rest: How to post composite IDs
public class DelegationEntity extends BaseEntity {
  /*
	//TODO: Area that this delegation is in
	@NonNull
	@NotNull
	@OneToOne
	public AreaModel area;
	*/

	/** Voter that delegated his right to vote to a proxy */
	@NonNull
	@NotNull
	@OneToOne
	public UserEntity fromUser;

  /** Proxy that receives the delegation and can now cast votes in place of the voter */
  @NonNull
  @NotNull
  @OneToOne
  public UserEntity toProxy;


	// This was a big one: Delegations are always transitive!
	// Because if my direct proxy forwards his right to vote and then his proxy votes, this is just as the direct proxy voted for himself.
	//public boolean transitive = true;

	/**
	 * When a voter wants to delegate his vote to a proxy, but that proxy is not a public proxy,
	 * then the delegation can only be requested until the proxy accepts it.
	 * We need to store the voters checksum for a delegation request, so that later when the proxy accepts
	 * the delegation request, the voters checksum can also be delegated to the proxies checksum.
	 */
	@OneToOne
	@JsonIgnore  // do not include this field in REST responses. RightToVotes are confidential
	RightToVoteEntity requestedDelegationFrom = null;

  /** When was the delegation to that proxy requested */
  LocalDateTime requestedDelegationAt = null;

	/**
	 * @return true if this delegation is requested. Proxy must still confirm.
	 */
  public boolean isDelegationRequest() {
  	return this.requestedDelegationFrom != null;
	}

	/**
	 * Build a delegation request
	 * @param fromUser this user delegates his right to vote
	 * @param proxy to this proxy
	 * @param rightToVoteModel the user's RightToVote that shall be delegated
	 * @return
	 */
	public static DelegationEntity buildDelegationRequest(UserEntity fromUser, UserEntity proxy, RightToVoteEntity rightToVoteModel) {
		//Implementation note: This is my first try for builders. Inside the model class itself. Let's see how that works out.
		//PRO: Easy, local.   CON(?)  Should models only be data models without any business logic.   But why?  This is just java code?
		//Separation of concerns: More complicated business logic should be inside a Service class.
		//A "builder" only creates a new object and sets some additional values, that the default RequiredArgs constructor does not set.
		DelegationEntity delegationRequest = new DelegationEntity(fromUser, proxy);
		delegationRequest.setRequestedDelegationFrom(rightToVoteModel);
		delegationRequest.setRequestedDelegationAt(LocalDateTime.now());
		return delegationRequest;
	}

	// Implementation notes:
  // - A delegation is always implicitly created by "fromUser"


	@Override
	public String toString() {
		final StringBuffer sb = new StringBuffer("DelegationModel[");
		sb.append("id=").append(id);
		sb.append(", fromUser=").append(fromUser.toStringShort());
		sb.append(", toProxy=").append(toProxy.toStringShort());
		sb.append(", requestedDelegationFrom=").append(requestedDelegationFrom);
		sb.append(", requestedDelegationAt=").append(requestedDelegationAt);
		sb.append(']');
		return sb.toString();
	}
}