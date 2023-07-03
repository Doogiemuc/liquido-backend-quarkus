package org.liquido.vote;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.*;
import org.liquido.user.UserEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * This entity is the digital representation of a voters right to vote. The right to vote is anonymous.
 * It is not directly linked to a voter. Votes are secret.
 * Only the voter knows his secret voterToken that he received via TeamGraphQL#getVoterToken
 * Only that voter can proof that this is his RightToVote by presenting his voterToken which hashes to
 * the stored hashedVoterToken.
 *
 * When a voter requests a voterToken for an area , then the server calculates two values:
 * 1. voterToken  = hash(user.email, userSecret, serverSecret, area)   This voterToken is returned to the voter.
 * 2. rightToVote = hash(voterToken, serverSecret)    The rightToVote is anonymously stored on the server.
 *
 * When a user wants to cast a vote, then he sends his voterToken for this area.
 * Then the server checks if he already knows the corresponding rightToVote value.
 * If yes, then the cast vote is valid and will be counted.
 */
@Data
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@EqualsAndHashCode(of = "hashedVoterToken", callSuper = false)
@Entity(name = "righttovote")
//@Table(name = "rightToVote", uniqueConstraints= {
//TODO:		@UniqueConstraint(columnNames = {"public_proxy_id"})  // A proxy cannot be public proxy more than once in one area.
//})
//TODO: RightToVote is <b>per area!</b>. The user needs to request a separate right to vote for each area.
public class RightToVoteEntity extends PanacheEntityBase {

	/**
	 * A checksum that validates a voter token.
	 * hashedVotertoken = hash(voterToken)
	 * This is also the ID of this entity.  ("Fachlicher Schlüssel")
	 */
	@Id
	@NonNull
	public String hashedVoterToken;

	/** A RightToVote are only valid for a given time */
	LocalDateTime expiresAt;

	/**
	 * A voter can delegate his right to vote to a proxy. This delegation is stored in the DelegationModel.
	 * Here we store the completely anonymous delegation from one checksum to another one.
	 * There is no relation between a checksum and a voter (except for public proxies).
	 */
	@ManyToOne
	@JsonIgnore                        // Do not reveal to whom a checksum is delegated
	RightToVoteEntity delegatedTo;

	// only expose WHETHER a checksum is delegated or not
	public boolean isDelegated() {
		return delegatedTo != null;
	}

	 /* List of checksums that are delegated to this as a proxy. This would be the inverse link of bidirectional delegatedToProxy association
	@OneToMany(mappedBy = "proxyFor", fetch = FetchType.EAGER)
	List<ChecksumModel> proxyFor;
  */

	/**
	 * If a user want's to be a public proxy, then he CAN link his user to his righttovote.
	 * Then voters can automatically delegate their vote to this proxy.
	 * Then the proxy does not need to accept delegations. They can automatically be delegated.
	 */
	@OneToOne
	UserEntity publicProxy = null;		// by default no username is stored together with a hashedVoterToken!!!

	public String toString() {
		StringBuffer buf = new StringBuffer();
		buf.append("RightToVote[");
		buf.append("hashedVoterToken="+this.getHashedVoterToken());			//MAYBE: do not expose sensible hashedVoterToken  ?
		buf.append(", publicProxy="+ (this.getPublicProxy() != null ? this.getPublicProxy().toStringShort() : "<null>"));
		buf.append(", delegatedTo.checksum="+ (this.getDelegatedTo() != null ? this.getDelegatedTo().getHashedVoterToken() : "<null>"));
		buf.append("]");
		return buf.toString();
	}

	//REFACTORED: I decided to store delegation requests in the DelegationModel
	/*
	 * When a voter wants to delegate his vote to a proxy, but that proxy is not a public proxy,
	 * then the delegated is requested until the proxy accepts it.

  @OneToOne
	UserModel requestedDelegationTo;

  /* When was the delegation to that proxy requested
  LocalDateTime requestedDelegationAt;

	*/

	//There is deliberately no createdBy in this class
	//For the same reason there is also no createdAt or updatedAt. They might lead to timing attacks.


	public static Optional<RightToVoteEntity> findByHashedVoterToken(String token) {
		return RightToVoteEntity.find("hashedVoterToken", token).firstResultOptional();
	}

	public static Optional<RightToVoteEntity> findByPublicProxy(UserEntity proxy) {
		return RightToVoteEntity.find("publicProxy", proxy).firstResultOptional();
	}

	/**
	 * Find the RightToVote of all voters that delegated their RightToVote to this proxy.
	 * @param delegatedTo the RightToVote of the proxy
	 * @return a list of all RightToVotes that are delegated to the given one
	 */
	public static List<RightToVoteEntity> findByDelegatedTo(RightToVoteEntity delegatedTo) {
		return RightToVoteEntity.list("delegatedTo", delegatedTo);
	}

}