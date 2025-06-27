package org.liquido.vote;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.liquido.delegation.DelegationEntity;
import org.liquido.poll.PollEntity;
import org.liquido.user.UserEntity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * This entity is the digital representation of a voters right to vote.
 * Every voter has one RightToVote for all polls in their LIQUIDO team.
 * But a voter needs a new VoterTokenEntity for every vote they want to cast.
 *
 * The RightToVote of a given user can be looked up by hashing their user info.
 * But for a RightToVote it cannot be found out whom it belongs to.
 *
 * Every Ballot is linked to one RightToVote. But not to the voter!
 */
@Data
@NoArgsConstructor
@RequiredArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity(name = "righttovote")
//@Table(name = "rightToVote", uniqueConstraints= {
//TODO:		@UniqueConstraint(columnNames = {"public_proxy_id"})  // A proxy cannot be public proxy more than once in one area.
//})
public class RightToVoteEntity extends PanacheEntityBase {
	// RightToVoteEntity extends PanacheEntityBase! not our own BaseEntity. No createdBy! And we have our own ID.
	//TODO: Should a RightToVote be per user? Or per user and team?
	//TODO: Should a RightToVote.hash include the user's passwordHash? Change password -> need to recreate all RightToVotes for this user.

	/**
	 * Hashed info about voter. The ID of this entity.
	 * Every voter has one RightToVote in LIQUIDO
   */
	@Id
	@NonNull
	@EqualsAndHashCode.Include      //FIX: only use this in Lombok equals and Hash code
	public String hashedVoterInfo;  // == SHA256(user.email + user.passwordHash + serverConfig.hashSecret)

	/** A RightToVote is only valid for a given time */
	@NonNull
	LocalDateTime expiresAt;

	// ======= Bidirectional hibernate relation: Voter ---(delegates to)---> Proxy

	//TODO: Create a custom Hibernate validator that prevents delegation to onself: https://docs.jboss.org/hibernate/stable/validator/reference/en-US/html_single/?v=9.0#section-class-level-constraints
	/**
	 * A voter can delegate his RightToVote to a proxy.
	 * This attribute anonymously delegates to the proxy's RightToVote.
	 */
	@ManyToOne
	@JoinColumn(name = "delegated_to", referencedColumnName = "hashedVoterInfo")
	@JsonIgnore
	RightToVoteEntity delegatedTo = null;

	/**
	 * A voter can delegate his right to vote to a proxy. Then the proxy will vote for him.
	 * The delegation is stored in the {@link DelegationEntity}.
	 * Here we only store the RightToVotes that have been delegated to this proxy.
	 * There is no direct relation between a RightToVote and a voter, because votes are anonymous.
	 */
	@OneToMany(mappedBy = "delegatedTo")
	@JsonIgnore  // Do not reveal if or to whom a RightToVote is delegated
	Set<RightToVoteEntity> delegations = new HashSet<>();

	/**
	 * If a user want's to be a public proxy, then they CAN link their user to their RightToVote.
	 * Then voters can automatically delegate their vote to this proxy.
	 * Then the proxy does not need to accept delegations. They can automatically be delegated.
	 */
	@OneToOne
	UserEntity publicProxy = null;

	/**
	 * Grant a user the right to vote.
	 * @return a RightToVote that you still need to persist
	 */
	public static RightToVoteEntity build(UserEntity voter, int expirationDays, String salt) {
		String hashedUserInfo = DigestUtils.sha3_256Hex(voter.email + voter.passwordHash + salt);
		// ConfigProvider.getConfig().getValue("liquido.right-to-vote-expiration-days", Integer.class); - would be possible but not clean. So we simply pass the salt as parameter.
		LocalDateTime expiresAt = LocalDateTime.now().plusDays(expirationDays);
		return new RightToVoteEntity(hashedUserInfo, expiresAt);
	}

	/**
	 * Delegate to a proxies right to vote
	 * Before you call this, check that this delegation is valid!
	 * Does not create a circle etc.
	 */
	public void delegateToProxy(RightToVoteEntity proxy) {
		// Remove from previous delegate if necessary. On both sides of the bidirectional association.
		if (this.getDelegatedTo() != null) {
			this.getDelegatedTo().getDelegations().remove(this);
		}

		// Set new delegation
		this.setDelegatedTo(proxy);
		proxy.getDelegations().add(this);
	}

	/**
	 * Removes the delegation from this RightToVote to its proxy (if any).
	 */
	public void removeDelegationToProxy() {
		if (this.delegatedTo != null) {
			this.delegatedTo.delegations.remove(this);
			this.delegatedTo = null;
		}
	}

	//REFACTORED: I decided to store delegation requests in the DelegationModel

	/**
	 * Lookup a RightToVoteEntity for a given hash.
	 * This is only possible in this direction: User -> RightToVote.
	 * It is not possible to find the corresponding user of one given RightToVote.
	 * @param hash the hashed info about a user
	 * @return the rightToVote with that hash value.
	 */
	public static Optional<RightToVoteEntity> findByHash(String hash) {
		return RightToVoteEntity.findByIdOptional(hash);
	}

	/**
	 * Lookup the RightToVote of a voter. This is used to find a submitted ballot
	 * and to {@link org.liquido.poll.PollService#findEffectiveProxy(PollEntity, UserEntity)}
	 *
	 * Only the server can look up the RightToVote for a given voter, because a hashSecret is included in the hash.
	 * It is not possible the other way round. It is not possible to find the voter that belongs to a right to vote.
	 * This is the beauty of the hash algorithm.
	 *
	 * @param voter a voter
	 * @return RightToVote of this voter if he has one.
	 */
	public static Optional<RightToVoteEntity> findByVoter(UserEntity voter, String salt) {
		String hashedUserInfo = DigestUtils.sha3_256Hex(voter.email + voter.passwordHash + salt);
		return RightToVoteEntity.findByIdOptional(hashedUserInfo);
	}

	/*
	public static Optional<RightToVoteEntity> findByPublicProxy(UserEntity proxy) {
		return RightToVoteEntity.find("publicProxy", proxy).firstResultOptional();
	}
	 */

	public String toString() {
		return new StringBuilder()
				.append("RightToVote[")  // do not expose hash
				.append("delegatedTo=").append(this.delegatedTo != null ? this.delegatedTo.hashedVoterInfo : "<null>")
				.append(", expiresAt=").append(this.expiresAt)
				.append(", isPublicProxy=").append(this.getPublicProxy() != null ? this.getPublicProxy().toStringShort() : "<null>")
				.append("]").toString();
	}


}