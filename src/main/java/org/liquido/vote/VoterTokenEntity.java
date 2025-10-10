package org.liquido.vote;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.scheduler.Scheduled;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.liquido.poll.PollEntity;

import java.time.LocalDateTime;

/**
 * One time token that is used for login without a password.
 * An OTT allows a user to login <b>once</b> with this token. After that
 * the token will be deleted. Each OTT has a limited time to live (TTL).
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(name = "votertokens")
public class VoterTokenEntity extends PanacheEntityBase {

	/**
	 * Hash of the user's voter token for this poll.
	 * A voter must present the original plain voterToken that hashes to this
	 * to be allowed to vote.
	 */
	@Id
	@NonNull
	String hashedVoterToken;

	/** The poll that a voter can cast his vote in with this OTT */
	@ManyToOne
	public PollEntity poll;

	/** An OTT is linked to a RightToVote, so that we can also create Polls for delegations. */
	@ManyToOne
	public RightToVoteEntity rightToVote;

	/** Of course, an OTT can only be used once! */
	public boolean used = false;

	/** Expiry date of token. After this date the token is not valid anymore. */
	@NonNull
	@NotNull
	public LocalDateTime expiresAt;

	/**
	 * Build and persist a new oneTimeToken for a given poll and link it to a RightToVote.
	 *
	 * @param hashedVoterToken the already hashed voterToken.
	 * @param poll A voter can only use this one time token to cast a vote in this poll.
	 * @param rightToVote link to (anonymous) RightToVote
	 * @param validHours how long is this OTT valid in HOURS
	 * @return the newly created and persisted OTT.
	 */
	public static VoterTokenEntity buildAndPersist(@NonNull String hashedVoterToken, @NonNull PollEntity poll, @NonNull RightToVoteEntity rightToVote, int validHours) {
		if (validHours <= 0) throw new RuntimeException("Cannot build OneTimeToken. ttl must be positive!");
		VoterTokenEntity ott = new VoterTokenEntity();
		ott.hashedVoterToken = hashedVoterToken;
		ott.poll = poll;
		ott.rightToVote = rightToVote;
		ott.expiresAt = LocalDateTime.now().plusHours(validHours);
		ott.persist();
		return ott;
	}

	public String toString() {
		return new StringBuilder()
				.append("VoterToken[")
				.append("for_poll.id=").append(this.poll.id)
				.append(", rightToVote=").append(this.rightToVote != null ? "yes" : "<NULL>!!! ERROR")
				.append("]").toString();
	}

	@Scheduled(every = "P1D")
	@Transactional
	public void deleteExpiredTokens() {
		VoterTokenEntity.delete("expiresAt < ?1", LocalDateTime.now());
	}
}