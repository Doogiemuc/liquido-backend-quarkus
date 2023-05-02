package org.liquido.security;

import io.smallrye.jwt.build.Jwt;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import java.util.Collections;

/**
 * Utility class to generate and then validate JsonWebTokens for Liquido.
 * Each JWT contains the user's <b>ID</b> as JWT "subject" claim.
 */
@Slf4j
@ApplicationScoped
public class JwtTokenUtils {

	public static final String LIQUIDO_ISSUER = "https://www.LIQUIDO.vote";

	public static final String LIQUIDO_USER_ROLE = "LIQUIDO_USER";

	@ConfigProperty(name = "liquido.jwt.expirationSecs")
	Long expirationSecs;

	public static final String TEAM_ID_CLAIM = "teamId";

	/**
	 * This generates a new JWT. This needs jwtSecret as input, so that only the server can
	 * generate JWTs. The userId becomes the JWT.subject and teamId is set as additional claim.
	 */
	public String generateToken(@NonNull String email, @NonNull Long teamId) {

		String JWT = Jwt
				.subject(email)
				//.upn("upn@liquido.vote")  // if upn is set, this will be used instead of subject   see JWTCallerPrincipal.getName()
				.issuer(LIQUIDO_ISSUER)
				.groups(Collections.singleton(LIQUIDO_USER_ROLE))  // role
				.claim(TEAM_ID_CLAIM, teamId)
				.expiresIn(expirationSecs)
				//.jws().algorithm(SignatureAlgorithm.HS256)
				.sign();

		/* DEPRECATED: old version with jjwt:io.jsonwebtoken.*
		Instant expiryDate = Instant.now().plusMillis(expirationSecs * 1000);
		return Jwts.builder()
				.setSubject(userId.toString())
				.claim(TEAM_ID_CLAIM, teamId)
				//.setClaims(claims)   //BUGFIX: This overwrites all other claims!!!  use addClaims
				.setIssuedAt(Date.from(Instant.now()))
				.setExpiration(Date.from(expiryDate))
				.signWith(SignatureAlgorithm.HS512, jwtSecret)
				.compact();

		 */

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

}