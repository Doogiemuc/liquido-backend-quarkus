package org.liquido.util;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;

import java.util.Optional;

/**
 * LIQUIDO configurations from application.properties
 */
@ConfigMapping(prefix = "liquido")
public interface LiquidoConfig {
	/** version of liquido backend API. Used in response to ping request */
	String apiVersion();

	/** URL of liquido frontend. Used as prefix for link in login email. */
	@NotNull
	String frontendUrl();

	/** Login link in email is only valid for this long. */
	@WithDefault("10")
	int loginLinkExpirationMinutes();

	/**
	 * Path (including the query parameter name) that an invite link points at, appended to
	 * {@link #frontendUrl()} and followed by the raw inviteCode.
	 *
	 * Configurable because LIQUIDO has two join flows: the welcome chat at "/welcome" and the
	 * dedicated join form at "/joinTeam". Switch this property to "/welcome?inviteCode=" to send
	 * invitees to the chat instead.
	 */
	@WithDefault("/joinTeam?inviteCode=")
	String inviteLinkPath();

	/**
	 * From-address for mails LIQUIDO sends.
	 *
	 * Note: an explicit setFrom() on a Mail overrides quarkus.mailer.from, and the two older mails in
	 * UserService hardcode this same address. Those are deliberately left alone; new mail reads it
	 * from config so it can differ per environment.
	 */
	@WithDefault("info@liquido.vote")
	String mailFrom();

	/** How long do polls run by default (TODO: future bigger LIQUIDO) */
	@NotNull
	int durationOfVotingPhase();

	/** When does a right to vote expire when a voter doesn't use it anymore? */
	@WithDefault("365")
	int rightToVoteExpirationDays();

	/** When does a voter token expire that a voter just fetched for a poll */
	@WithDefault("20")
	int voterTokenExpirationMinutes();

	/**
	 * Length of team invite codes.
	 *
	 * This used to say "Must match frontend config!!!" - and that was the whole problem: the value
	 * lived in two places and nothing enforced the match. The frontend now fetches these through
	 * {@code query liquidoConfig} at startup instead of hardcoding its own copy.
	 */
	@WithDefault("8")
	int inviteCodeLength();

	/** Keep passwords secure! */
	@WithDefault("10")
	int minPasswordLength();

	// ============ Validation rules the FRONTEND must agree with ============
	//
	// Everything below is served to the frontend by ConfigGraphQL so the two cannot drift. Before
	// this existed, config.common.js carried its own copies, and proposalDescriptionMinLength had
	// already drifted to 10 against the entity's @Size(min=20) - so a user could fill in a form the
	// client accepted and the server then rejected.

	/** Minimum length of a user's nickname. */
	@WithDefault("3")
	int usernameMinLength();

	/** Minimum length of a poll title. Short is fine, but not so short that it invites spam. */
	@WithDefault("5")
	int pollTitleMinLength();

	/** Minimum length of a proposal title. */
	@WithDefault("3")
	int proposalTitleMinLength();

	/**
	 * Minimum length of a proposal description.
	 *
	 * MUST stay in sync with {@code @Size(min = ...)} on {@code ProposalEntity.description}, which is
	 * what actually rejects a too short description. LiquidoConfigMatchesEntityTest asserts the two
	 * agree, so a change to one without the other fails the build rather than surfacing as a
	 * confusing server-side rejection of a form the client had accepted.
	 */
	@WithDefault("20")
	int proposalDescriptionMinLength();


	/** Used for login with google */
	@NonNull
	String googleClientId();

	/**
	 * A secret only know to the server. It is used
	 *  - to create a RightToVote
	 *  - to create one time VoterToken
	 *  - to sign proxy delegations
	 */
	@NotNull
	String hashSecret();

	/** (optional) login token that can be used to login during dev. (This CANNOT be used PROD!) */
	@WithName("dev-login-token")
	Optional<String> devLoginTokenOpt();

	/* MAYBE: would also be possible. But I don't like the RuntimeException.
	default String devLoginToken() {
		return devLoginTokenOpt().orElseThrow(
				() -> new RuntimeException("DevLogin token is not defined in config")
		);
	}
	*/

	/** (optional) token that is used in cypress E2E test to automatically test the full the password reset process (This CANNOT be used in PROD!) */
	@WithName("test-password-reset-token")
	Optional<String> testPasswordResetTokenOpt();

	/** Login JsonWebtoken */
	Jwt jwt();

	interface Jwt {
		@WithDefault("60")
		Long expirationSecs();
	}

	/** Polly: the small, passkey-only sibling of a LIQUIDO poll. */
	Polly polly();

	interface Polly {
		/**
		 * Secret for the owner-key and voter-key HMACs.
		 *
		 * Deliberately separate from {@link #hashSecret()}, which is pinned forever because
		 * RightToVote ids (and therefore every ballot's foreign key) are derived from it.
		 * This one can be rotated independently; doing so resets polly ownership and voting
		 * history, which is contained damage.
		 */
		@NotNull
		String hmacSecret();

		/**
		 * How long a polly passkey session lasts. Long on purpose: ~30 days means one tap on
		 * the first visit and none afterwards, which is what lets a polly have no login screen.
		 */
		@WithDefault("2592000")
		Long jwtExpirationSecs();
	}

	/** Sending SMS */
	Twilio twilio();
	interface Twilio {
		String accountSid();
		String authToken();
		String serviceSid();
	}

}