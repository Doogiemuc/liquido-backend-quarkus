package org.liquido.vote;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.liquido.delegation.DelegationEntity;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;
import org.liquido.user.UserEntity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * This entity is the digital representation of a voters right to vote.
 *
 * Every voter has one RightToVote for all polls in their LIQUIDO team.
 * A right to vote expires when not used for too long.
 * A voter needs a fresh OneTimeVotingToken for every vote they want to cast.
 *
 * The RightToVote of a given user can be looked up by hashing their user info.
 * But for a given RightToVote it cannot be determined to whom it belongs to.
 *
 * A RightToVote may be delegated to a proxy. Then the proxy can vote for the delegee.
 *
 * Every Ballot is linked to one RightToVote. But not to the voter!
 */
@Slf4j
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

	/**
	 * Hashed info about voter. The ID of this entity.
	 * Every voter has one RightToVote in LIQUIDO.
	 * Deliberately does NOT include the user's passwordHash: see {@link #calcHashedVoterInfo}.
   */
	@Id
	@NonNull
	@EqualsAndHashCode.Include      //FIX: only use this in Lombok equals and Hash code
	public String hashedVoterInfo;  // == SHA3-256(user.email + serverConfig.hashSecret)

	/** A RightToVote is only valid for a given time */
	@NonNull
	LocalDateTime expiresAt;

	// ======= Bidirectional hibernate relation: Voter ---(delegates to)---> Proxy

	//MAYBE: Create a custom Hibernate validator that prevents delegation to oneself: https://docs.jboss.org/hibernate/stable/validator/reference/en-US/html_single/?v=9.0#section-class-level-constraints
	//       But this is also checked in DelegationService.java
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
	@ManyToOne
	UserEntity publicProxy = null;

	/**
	 * The team this right to vote is scoped to.
	 *
	 * <p>A person in three teams holds three unrelated rights to vote, so two of them cannot be shown
	 * to belong to one person without the server secret -- two unrelated LIQUIDO teams cannot
	 * correlate their members even with full access to both databases. Team scope is also exactly
	 * right for delegation: a proxy in one team has no standing in another team's poll, so the
	 * delegation graph is naturally partitioned rather than being one global structure.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "team_id")
	@JsonIgnore     // Do not expose which team an anonymous right to vote belongs to
	TeamEntity team;

	/**
	 * Which version of the server secret produced {@link #hashedVoterInfo}.
	 * See {@link LiquidoConfig#secretForVersion(int)} for why this exists.
	 */
	@Column(name = "key_version")
	int keyVersion;

	/** Separator for every keyed-hash input here. Neither an email nor a decimal id can contain it. */
	private static final String SEP = "|";

	/**
	 * Derive a voter's {@link #hashedVoterInfo} for one team.
	 *
	 * <p>HMAC, not {@code hash(data + secret)}. A plain hash proves nothing about who computed it,
	 * and concatenating the secret onto the data is a construction HMAC exists to replace. The
	 * explicit separator matters too: without it {@code email="ab"} with one team and
	 * {@code email="a"} with another could in principle produce the same input string.
	 *
	 * <p>Deliberately does NOT include {@link UserEntity#passwordHash}: that would make a
	 * password change silently destroy the user's right to vote and orphan their ballots
	 * (this is the {@code @Id} of this entity).
	 */
	private static String calcHashedVoterInfo(UserEntity voter, TeamEntity team, String secret) {
		return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secret).hmacHex(voter.email + SEP + team.id);
	}

	/**
	 * Derive the poll-scoped pseudonym this right to vote casts a ballot under.
	 *
	 * <p>This is what a ballot stores; the right to vote itself is never written to a ballot row.
	 * The mapping is never persisted anywhere -- the server re-derives it on demand, because it
	 * holds the secret. So one voter's ballots in ten polls of one team carry ten unrelated
	 * pseudonyms, and an attacker with the whole database and no secret cannot group them into a
	 * voting history.
	 *
	 * <p>Derived under the key version that produced this right to vote, so the pseudonym stays
	 * stable for the life of the row and previously cast ballots remain findable.
	 */
	public String deriveBallotPseudonym(Long pollId, LiquidoConfig config) throws LiquidoException {
		String secret = config.secretForVersion(this.keyVersion);
		return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secret).hmacHex(this.hashedVoterInfo + SEP + pollId);
	}

	/**
	 * Grant a user the right to vote IN ONE TEAM.
	 * @return a RightToVote that you still need to persist
	 */
	public static RightToVoteEntity build(UserEntity voter, TeamEntity team, int expirationDays, LiquidoConfig config) {
		String hashedUserInfo = calcHashedVoterInfo(voter, team, config.hashSecret());
		// Deliberately logs neither the voter nor the hash: this row is the voter <-> ballot hinge.
		log.debug("Creating new RightToVote in team.id={}", team.id);
		RightToVoteEntity rightToVote = new RightToVoteEntity(hashedUserInfo, expiryFromNow(expirationDays));
		rightToVote.team = team;
		rightToVote.keyVersion = config.hashSecretVersion();
		return rightToVote;
	}

	/**
	 * The one place that turns "expiration DAYS" into a timestamp.
	 * It exists because the same arithmetic used to be duplicated at three call sites, and two
	 * of them said plusHours() - so casting a vote silently shortened a right to vote from a
	 * year to about a fortnight instead of renewing it.
	 */
	private static LocalDateTime expiryFromNow(int expirationDays) {
		return LocalDateTime.now().plusDays(expirationDays);
	}

	/**
	 * Renew this right to vote, as happens whenever its owner casts a vote.
	 * @param expirationDays validity from now, in DAYS (liquido.right-to-vote-expiration-days)
	 */
	public void renewExpiry(int expirationDays) {
		this.expiresAt = expiryFromNow(expirationDays);
	}

	/** Check if this RightToVot is not yet expired */
	public boolean isValid() {
		return this.expiresAt.isAfter(LocalDateTime.now());
	}

	/**
	 * Revive a lapsed right to vote, but only for someone who is still a member of its team.
	 *
	 * <h2>Why this exists</h2>
	 *
	 * Expiry used to be a one-way door. {@link #renewExpiry} was reachable only from the two casting
	 * paths, and both refuse an already-expired right to vote before reaching it; the only other
	 * writer, {@code grantRightToVote()}, correctly short-circuits when a row already exists, since a
	 * second row cannot be written under the same derived primary key. A member who simply did not
	 * vote for {@code right-to-vote-expiration-days} was therefore locked out permanently, with no
	 * transition back short of editing the database. In a voting system that is disenfranchisement,
	 * and it falls hardest on the least engaged voters.
	 *
	 * <h2>Why membership is what gets re-checked</h2>
	 *
	 * The entitlement is team MEMBERSHIP: a right to vote is granted on joining and derived from
	 * {@code HMAC(secret, email | teamId)}. Expiry marks the derived value stale, it does not withdraw
	 * the entitlement. Reviving it for a current member restores only what membership already grants.
	 * Reviving it for a non-member would manufacture an entitlement that no longer exists, which is
	 * what keeps a departed member's leftover row dead.
	 *
	 * @param team the team this right to vote belongs to
	 * @param expirationDays validity from now, in DAYS (liquido.right-to-vote-expiration-days)
	 * @return true if this right to vote is usable now -- either it was already valid, or it was
	 *         renewed because its holder is still a member
	 */
	public boolean renewIfMemberOf(TeamEntity team, UserEntity voter, int expirationDays) {
		if (this.isValid()) return true;
		if (team == null || !team.isMember(voter)) return false;
		this.renewExpiry(expirationDays);
		return true;
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
	 * Lookup the RightToVote of a voter IN ONE TEAM.
	 *
	 * Only the server can look up the RightToVote for a given voter, because the server secret is
	 * mixed into the derivation. It is not possible the other way round: a right to vote cannot be
	 * resolved back to the voter it belongs to. That asymmetry is the whole point.
	 *
	 * <p>Tries the current key version first, then any retired secret. A rotation therefore does not
	 * lock voters out of rights to vote written under the previous secret -- see
	 * {@link LiquidoConfig#previousHashSecrets()} for why that alone does not complete a rotation.
	 *
	 * @param voter a voter
	 * @param team the team to look up the voter's right to vote in
	 * @return the voter's RightToVote in that team, if they have one
	 */
	public static Optional<RightToVoteEntity> findByVoterAndTeam(UserEntity voter, TeamEntity team, LiquidoConfig config) {
		// Deliberately does NOT filter on isValid(). Collapsing "expired" into "not found" here would
		// make every caller report the wrong thing -- a lapsed voter would be told they have no right
		// to vote at all -- and it would break the read-only paths that must keep working after expiry.
		// Validity is a per-operation decision instead, and the callers divide cleanly in two:
		//
		//   Exercising the right (casting a vote, delegating): must be live, and renews it on use for
		//   a current member -- see renewIfMemberOf().
		//
		//   Reading history (looking up your own ballot, resolving the effective proxy, counting
		//   delegations): works on an expired right to vote on purpose. A voter whose right lapsed
		//   after they voted must still be able to verify the ballot they cast, or expiry would
		//   silently retract the verifiability the system promises them.
		Optional<RightToVoteEntity> current =
				RightToVoteEntity.findByIdOptional(calcHashedVoterInfo(voter, team, config.hashSecret()));
		if (current.isPresent()) return current;

		for (String retiredSecret : config.previousHashSecrets().values()) {
			Optional<RightToVoteEntity> old =
					RightToVoteEntity.findByIdOptional(calcHashedVoterInfo(voter, team, retiredSecret));
			if (old.isPresent()) return old;
		}
		return Optional.empty();
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