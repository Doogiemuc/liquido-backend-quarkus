package org.liquido.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Secure handling of passwords with BCRYPT
 */
@ApplicationScoped
public class PasswordService {

	/** Hash a plain text password */
	public String hashPassword(String plainPassword) {
		return BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray());
	}

	/** verify a plain password: Hash it and compare it with the user's stored hash */
	public boolean verifyPassword(String plainPassword, String storedHash) {
		BCrypt.Result result = BCrypt.verifyer().verify(plainPassword.toCharArray(), storedHash);
		return result.verified;
	}
}