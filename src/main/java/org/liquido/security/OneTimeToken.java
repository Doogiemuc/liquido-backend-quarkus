package org.liquido.security;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.*;
import org.liquido.user.UserEntity;

import javax.persistence.Entity;
import javax.persistence.OneToOne;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * One time token that is used for login without a password.
 * A OTT allows a user to login <b>once</b> with this token. After that
 * the token will be deleted. Each OTT has a limited time to live (TTL).
 */
@Data
@NoArgsConstructor
@RequiredArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(name = "onetimetokens")
public class OneTimeToken extends PanacheEntity {
	/** Nonce of the token. Can for example be a UUID. */
	@NonNull
	@NotNull
	String nonce;

	/** LIQUIDO User that this token belongs to. Only this user is allowed to use this token.*/
	@NonNull
	@NotNull
	@OneToOne
	UserEntity user;

	/** Expiry date of token. After this date the token is not valid anymore. */
	@NonNull
	@NotNull
	LocalDateTime validUntil;

	public static Optional<OneTimeToken> findByNonce(String nonce) {
		return OneTimeToken.find("nonce", nonce).firstResultOptional();
	}
}
