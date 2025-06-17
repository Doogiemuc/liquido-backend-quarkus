package org.liquido.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * Secure hashing of passwords with BCRYPT.
 * Keep in mind that everytime you call hashPassword, a different hash will be generated,
 * even when you provide the same plainPassword. See Bcrypt -> salting
 */
public class PasswordServiceBcrypt {

	/** Hash a plain text password */
	public static String hashPassword(String plainPassword) {
		return BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray());
	}

	/** verify a plain password: Hash it and compare it with the user's stored hash */
	public static boolean verifyPassword(String plainPassword, String storedHash) {
		BCrypt.Result result = BCrypt.verifyer().verify(plainPassword.toCharArray(), storedHash);
		return result.verified;
	}
}