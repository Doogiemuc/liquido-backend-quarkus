package org.liquido.delegation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.user.UserEntity;
import org.liquido.vote.RightToVoteEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Delegation from a user to a proxy in a given area.
 * A user can only have none or exactly one proxy per area.
 * One user may be the proxy for several "delegees" in one area.
 * A delegation is always implicitly created by fromUser, since a user may only choose proxy for themselves.
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
public class DelegationEntity extends LiquidoBaseEntity {
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

  /**
   * Proxy that receives the delegation and can now cast votes in place of the voter.
   * MUST be @ManyToOne, not @OneToOne: one proxy can receive delegations from many voters.
   * @OneToOne here previously put a UNIQUE constraint on toproxy_id, silently limiting every
   * proxy in the system to at most one delegee, ever -- breaking the core proxy feature.
   */
  @NonNull
  @NotNull
  @ManyToOne
  public UserEntity toProxy;


	// This was a big one: Delegations are always transitive!
	// Because if my direct proxy forwards his right to vote and then his proxy votes, this is just as if the direct proxy voted for himself.
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
	 * Find all requested delegations for a given proxy.
	 * @param proxy the proxy
	 * @return list of delegation requests to this proxxy
	 */
	public static List<DelegationEntity> findByToProxy(UserEntity proxy) {
		return DelegationEntity.find("toProxy", proxy).list();
	}

	public static List<DelegationEntity> findDelegationRequestsTo(UserEntity proxy) {
		// "requestedDelegationFrom != null" silently matches nothing here: Hibernate does not translate
		// "!=" against an entity-valued (association) field into a working null check. "is not null" does.
		return DelegationEntity.find("toProxy = ?1 and requestedDelegationFrom is not null", proxy).list();
	}

	/**
	 * Find the (at most one, per the {@code fromuser_id} unique constraint) delegation row for a voter.
	 * This is the single source of truth for who a voter currently delegates to -- pending request or
	 * already accepted, see {@link #isDelegationRequest()}.
	 */
	public static Optional<DelegationEntity> findByFromUser(UserEntity fromUser) {
		return DelegationEntity.find("fromUser", fromUser).firstResultOptional();
	}

	/**
	 * @return true if this delegation is requested. Proxy must still confirm.
	 */
  public boolean isDelegationRequest() {
  	return this.requestedDelegationFrom != null;
	}

	/**
	 * Create (or update) a delegation request from fromUser to proxy, and persist it.
	 * Upserts on fromUser's existing row rather than always inserting: the {@code fromuser_id}
	 * unique constraint means a plain insert on a re-request (e.g. after the first request was
	 * accepted, or targeted a different proxy) would violate the constraint and 500.
	 * @param fromUser this user delegates his right to vote
	 * @param proxy to this proxy
	 * @param rightToVoteModel the user's RightToVote that shall be delegated
	 * @return the persisted delegation request
	 */
	public static DelegationEntity createDelegationRequest(UserEntity fromUser, UserEntity proxy, RightToVoteEntity rightToVoteModel) {
		DelegationEntity delegationRequest = DelegationEntity.findByFromUser(fromUser).orElseGet(() -> new DelegationEntity(fromUser, proxy));
		delegationRequest.setToProxy(proxy);
		delegationRequest.setRequestedDelegationFrom(rightToVoteModel);
		delegationRequest.setRequestedDelegationAt(LocalDateTime.now());
		delegationRequest.persist();
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