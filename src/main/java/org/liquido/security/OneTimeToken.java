package org.liquido.security;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToOne;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.liquido.user.UserEntity;

import java.time.LocalDateTime;

/**
 * One time token that is used for login without a password or to reset a user's password.
 * A OTT allows a user to login <b>once</b> with this token. After that
 * the token will be deleted. Each OTT has a limited time to live (TTL).
 */
@Data
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(name = "onetimetokens")
public class OneTimeToken extends PanacheEntity {
	/** Nonce of the token. Can, for example, be a UUID. */
	@NonNull
	@NotNull
	String nonce;

	/** LIQUIDO User that this token belongs to. Only this user is allowed to use this token.*/
	@NonNull
	@NotNull
	@OneToOne
	UserEntity user;

	/** Expiry date of token. After this date, the token is not valid anymore. */
	@NonNull
	@NotNull
	LocalDateTime validUntil;

	public static void deleteUsersOldTokens(UserEntity user) {
		OneTimeToken.delete("user", user);
		//OneTimeToken.find("user", user).stream().forEach(token -> token.delete());
	}

	@Scheduled(every = "P1D")
	@Transactional
	public void deleteExpiredTokens() {
		OneTimeToken.delete("validUntil < ?1", LocalDateTime.now());
	}
}