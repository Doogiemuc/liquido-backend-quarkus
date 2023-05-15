package org.liquido.security;

import io.smallrye.jwt.build.Jwt;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class to generate and then validate JsonWebTokens for Liquido.
 * Each JWT contains the user's <b>ID</b> as JWT "subject" claim.
 */
@Slf4j
@RequestScoped
public class JwtTokenUtils {

  // https://quarkus.io/guides/security-authentication-mechanisms-concept#smallrye-jwt-authentication
	// https://quarkus.io/guides/security-architecture-concept

	public static final String LIQUIDO_ISSUER = "https://www.LIQUIDO.vote";

	public static final String LIQUIDO_USER_ROLE = "LIQUIDO_USER";
	public static final String LIQUIDO_ADMIN_ROLE = "LIQUIDO_ADMIN";

	@ConfigProperty(name = "liquido.jwt.expirationSecs")
	Long expirationSecs;

	@Inject
	JsonWebToken jwt;

	public static final String TEAM_ID_CLAIM = "teamId";

	/**
	 * This generates a new JWT. This needs jwtSecret as input, so that only the server can
	 * generate JWTs. The userId becomes the JWT.subject and teamId is set as additional claim.
	 */
	public String generateToken(@NonNull String email, @NonNull Long teamId, boolean isAdmin) {
		Set groups = new HashSet();
		groups.add(LIQUIDO_USER_ROLE);   // everyone is a liquido_user
		if (isAdmin) groups.add(LIQUIDO_ADMIN_ROLE);
		String JWT = Jwt
				.subject(email)
				//.upn("upn@liquido.vote")  // if upn is set, this will be used instead of subject   see JWTCallerPrincipal.getName()
				.issuer(LIQUIDO_ISSUER)
				.groups(groups)
				.claim(TEAM_ID_CLAIM, teamId+"")  // better put strings into claims
				.expiresIn(expirationSecs)
				//.jws().algorithm(SignatureAlgorithm.HS256)
				.sign();

		return JWT;
	}

	/*
	 * Validates if a token has the correct signature and is not expired or unsupported.
	 * @return true when token is valid
	 * @throws LiquidoException when token is invalid.

	public boolean validateToken(String authToken) throws LiquidoException {
		try {
			Jwts.parser().setSigningKey(jwtSecret).parseClaimsJws(authToken);
			return true;
		} catch (SignatureException ex) {
			log.debug("Invalid JWT signature");
			throw new LiquidoException(LiquidoException.Errors.JWT_TOKEN_INVALID, "Incorrect signature");
		} catch (MalformedJwtException ex) {
			log.debug("Invalid JWT token");
			throw new LiquidoException(LiquidoException.Errors.JWT_TOKEN_INVALID, "Malformed jwt token");
		} catch (ExpiredJwtException ex) {
			log.debug("Expired JWT token");
			throw new LiquidoException(LiquidoException.Errors.JWT_TOKEN_EXPIRED, "Token expired. Refresh required.");
		} catch (UnsupportedJwtException ex) {
			log.debug("Unsupported JWT token");
			throw new LiquidoException(LiquidoException.Errors.JWT_TOKEN_INVALID, "Unsupported JWT token");
		} catch (IllegalArgumentException ex) {
			log.debug("JWT claims string is empty.");
			throw new LiquidoException(LiquidoException.Errors.JWT_TOKEN_INVALID, "Illegal argument token");
		}
	}

	 */


	private UserEntity currentUser = null;

	private TeamEntity currentTeam = null;

	/**
	 * Get the currently logged-in user.
	 * The UserEntity will lazily be loaded the first time you call this
	 * and then cached for succeeding calls.
	 * @return Optional.of(UserEntity) or Optional.empty() if not logged in
	 */
	public Optional<UserEntity> getCurrentUser() {
		if (this.currentUser != null) return Optional.of(currentUser);
		if (jwt == null || DoogiesUtil.isEmpty(jwt.getName())) return Optional.empty();
		String email = jwt.getName();
		log.debug("Loading current user from DB ... " + email);
		Optional<UserEntity> userOpt = UserEntity.findByEmail(email);
		if (userOpt.isEmpty()) {
			log.warn("Valid JWT, but user <" + email + "> not found in DB!");
			return userOpt;
		}
		this.currentUser = userOpt.get();
		return userOpt;
	}



	// I could also augment the Quarkus SecurityIdentity:  https://quarkus.io/guides/security-customization#security-identity-customization
	// and add my LIQUIDO UserEntity as attribute



	public Optional<TeamEntity> getCurrentTeam() {
		Optional<UserEntity> userOpt = getCurrentUser();
		if (userOpt.isEmpty()) return Optional.empty();
		Long teamId = Long.valueOf(jwt.getClaim(JwtTokenUtils.TEAM_ID_CLAIM));
		Optional<TeamEntity> teamOpt = TeamEntity.findByIdOptional(teamId);
		if (teamOpt.isEmpty()) {
			log.warn("Valid JWT for " + userOpt.get().toStringShort() + ", but not logged into any team");
			return teamOpt;
		}
		this.currentTeam = teamOpt.get();
		return teamOpt;
	}


}