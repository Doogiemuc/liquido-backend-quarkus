package org.liquido.user;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.team.TeamDataResponse;
import org.liquido.util.LiquidoException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>Confirming an email address</h1>
 *
 * Verification is deliberately OPTIONAL - it never blocks registering, joining or voting. It is a
 * signal that the address really belongs to the person, nothing more.
 *
 * The property that matters most here is what verification does NOT do: it grants no session and no
 * privileges. That is what makes it safe to send as a clickable link that never expires, and it is
 * what keeps the welcome mail from becoming a way to log in.
 */
@QuarkusTest
public class EmailVerificationTests {

	@Inject
	LiquidoTestUtils util;

	@Inject
	UserService userService;

	/** Read a user fresh from the DB, so we assert on stored state and not on a stale instance. */
	@Transactional
	UserEntity reload(String email) {
		return UserEntity.findByEmail(email).orElseThrow();
	}

	@Test
	@DisplayName("A newly registered user starts out unverified")
	public void newUserIsNotVerifiedYet() {
		TeamDataResponse admin = util.createFreshTeam("VerifyNew");
		assertFalse(reload(admin.user.getEmail()).emailVerified,
				"registration must not pretend the address was confirmed");
	}

	@Test
	@DisplayName("Clicking the link confirms the address")
	public void nonceVerifiesTheAddress() throws LiquidoException {
		TeamDataResponse admin = util.createFreshTeam("VerifyOk");
		String email = admin.user.getEmail();

		String nonce = userService.issueEmailVerificationNonce(reload(email));
		assertNotNull(nonce, "an unverified user must get a nonce to put in the mail");

		userService.verifyEmail(nonce);

		assertTrue(reload(email).emailVerified, "the address must be marked verified");
	}

	@Test
	@DisplayName("The nonce is single use - a second click is refused")
	public void nonceCannotBeReplayed() throws LiquidoException {
		TeamDataResponse admin = util.createFreshTeam("VerifyReplay");
		String nonce = userService.issueEmailVerificationNonce(reload(admin.user.getEmail()));
		userService.verifyEmail(nonce);

		// The address stays verified either way; what must not happen is the token staying live.
		LiquidoException ex = assertThrows(LiquidoException.class, () -> userService.verifyEmail(nonce));
		assertEquals(LiquidoException.Errors.EMAIL_VERIFICATION_TOKEN_INVALID, ex.getError());
		assertTrue(reload(admin.user.getEmail()).emailVerified, "it stays verified after a replay attempt");
	}

	@Test
	@DisplayName("An unknown token is refused")
	public void unknownNonceIsRefused() {
		LiquidoException ex = assertThrows(LiquidoException.class,
				() -> userService.verifyEmail("not-a-real-nonce-" + System.currentTimeMillis()));
		assertEquals(LiquidoException.Errors.EMAIL_VERIFICATION_TOKEN_INVALID, ex.getError());
	}

	@Test
	@DisplayName("Re-issuing invalidates the previous link")
	public void reissuingReplacesTheOldNonce() throws LiquidoException {
		TeamDataResponse admin = util.createFreshTeam("VerifyReissue");
		String email = admin.user.getEmail();

		String first = userService.issueEmailVerificationNonce(reload(email));
		String second = userService.issueEmailVerificationNonce(reload(email));
		assertNotEquals(first, second, "a resent welcome mail must carry a fresh nonce");

		// Only the newest link works - otherwise every mail ever sent stays usable forever.
		LiquidoException ex = assertThrows(LiquidoException.class, () -> userService.verifyEmail(first));
		assertEquals(LiquidoException.Errors.EMAIL_VERIFICATION_TOKEN_INVALID, ex.getError());

		userService.verifyEmail(second);
		assertTrue(reload(email).emailVerified);
	}

	@Test
	@DisplayName("An already verified user gets no new nonce")
	public void verifiedUserGetsNoFurtherNonce() throws LiquidoException {
		TeamDataResponse admin = util.createFreshTeam("VerifyAgain");
		String email = admin.user.getEmail();
		userService.verifyEmail(userService.issueEmailVerificationNonce(reload(email)));

		assertNull(userService.issueEmailVerificationNonce(reload(email)),
				"nothing left to confirm, so the welcome mail leaves the section out");
	}

	@Test
	@DisplayName("SECURITY: the nonce is never exposed through GraphQL or JSON")
	public void nonceIsNotExposed() throws NoSuchFieldException {
		// UserEntity's public fields are exposed by SmallRye GraphQL unless annotated, and anyone able
		// to read another user's nonce could mark their address verified. Same pair of annotations
		// totpFactorSid uses. Asserted rather than trusted because the field is public and one careless
		// refactor - or dropping an annotation while moving code - silently puts it in the schema.
		// (Checked against the live schema when this was written: emailVerified is exposed, the nonce
		// is not.)
		var field = UserEntity.class.getDeclaredField("emailVerificationNonce");
		assertNotNull(field.getAnnotation(org.eclipse.microprofile.graphql.Ignore.class),
				"emailVerificationNonce MUST carry @Ignore or it becomes a queryable GraphQL field");
		assertNotNull(field.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class),
				"emailVerificationNonce MUST carry @JsonIgnore or it can be serialised into a response");
	}

	@Test
	@DisplayName("A resent link replaces the old one, and the old one stops working")
	public void resendIssuesAFreshLinkAndKillsTheOld() throws LiquidoException {
		// This is the property that makes "send me a new one" safe: it is a REPLACEMENT, not a second
		// parallel way in. Otherwise every link ever mailed would stay live forever.
		TeamDataResponse admin = util.createFreshTeam("VerifyResend");
		String email = admin.user.getEmail();

		String original = userService.issueEmailVerificationNonce(reload(email));
		String resent = userService.issueEmailVerificationNonce(reload(email));

		assertNotEquals(original, resent);
		assertThrows(LiquidoException.class, () -> userService.verifyEmail(original),
				"the link from the first mail must stop working once a new one was sent");

		userService.verifyEmail(resent);
		assertTrue(reload(email).emailVerified);
	}
}
