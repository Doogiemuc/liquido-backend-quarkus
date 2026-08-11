package org.liquido.polly;

import io.quarkus.security.webauthn.WebAuthnCredentialRecord;
import io.quarkus.security.webauthn.WebAuthnSecurity;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.JwtTokenUtils;

import java.time.LocalDateTime;

/**
 * The passkey ceremony for Polly - the only way anybody ever "signs in" to a polly.
 *
 * <p>Separate from {@link org.liquido.security.webauthn.WebAuthnRestApi} because that one
 * assumes a logged-in team member with an email address, which a polly voter never is. These
 * four endpoints are callable with no session at all and hand back a polly-scoped JWT.
 *
 * <h3>Two properties that make a polly work</h3>
 * <ul>
 *   <li><b>Discoverable credentials.</b> {@code quarkus.webauthn.resident-key} defaults to
 *       {@code REQUIRED}, so the authenticator stores the credential and can offer it back
 *       without us naming a user.</li>
 *   <li><b>Empty {@code allowCredentials} on login.</b> {@code getLoginChallenge(null, ctx)}
 *       asks for no particular credential, so the browser offers whatever passkey it holds for
 *       this domain. That is precisely what lets the share link carry no secret: whoever opens
 *       it is identified by their own device.</li>
 * </ul>
 *
 * <h3>The challenge travels in a cookie</h3>
 * Quarkus stores the WebAuthn challenge in a cookie, so the frontend must send
 * {@code withCredentials: true} (it does) and the browser must be willing to return the cookie
 * cross-origin - hence {@code quarkus.webauthn.cookie-same-site=None} and an explicit CORS
 * origin. A {@code "Missing challenge"} failure almost always means one of those is missing.
 */
@Slf4j
@Path("/polly/webauthn")
@Produces(MediaType.APPLICATION_JSON)
public class PollyWebAuthnRestApi {

	/**
	 * Carries the freshly minted user handle from the options call to the register call.
	 *
	 * <p>Needed because WebAuthn keys a credential on {@code (rpId, user.id)}: if every polly
	 * registration reused one {@code user.id}, a second registration on a device would silently
	 * <b>replace</b> the first, and one person could never be two voters. The handle must be
	 * unique per identity and identical across both legs of the ceremony, and the registration
	 * response does not echo it back - so it rides a cookie, like the challenge itself.
	 */
	static final String USER_HANDLE_COOKIE = "polly_user_handle";

	private static final long HANDLE_COOKIE_MAX_AGE_SECONDS = 300;   // the ceremony takes seconds

	private final WebAuthnSecurity webAuthnSecurity;
	private final JwtTokenUtils jwtTokenUtils;
	private final PollyKeys pollyKeys;

	public PollyWebAuthnRestApi(WebAuthnSecurity webAuthnSecurity, JwtTokenUtils jwtTokenUtils, PollyKeys pollyKeys) {
		this.webAuthnSecurity = webAuthnSecurity;
		this.jwtTokenUtils = jwtTokenUtils;
		this.pollyKeys = pollyKeys;
	}

	/** What the frontend expects back from both /register and /login. */
	public record PollyJwtResponse(String jwt) {
	}

	// ================================================================= REGISTRATION (attestation)

	/**
	 * Registration options for a brand new polly passkey. No email, no username, no session.
	 */
	@GET
	@Path("/register-options-challenge")
	@PermitAll
	@Blocking
	public String registerOptions(RoutingContext ctx) {
		String userHandle = pollyKeys.newUserHandle();
		setUserHandleCookie(ctx, userHandle);

		log.debug("Polly WebAuthn GET /register-options-challenge for a new handle");
		// "Polly" is the display name the passkey picker shows. The handle is the opaque user.id.
		var creationOptions = webAuthnSecurity.getRegisterChallenge(userHandle, "Polly", ctx).await().indefinitely();
		return webAuthnSecurity.toJsonString(creationOptions);   // webauthn4j's own Jackson module
	}

	/**
	 * Verify the attestation, remember the credential, and hand back a polly session.
	 */
	@POST
	@Path("/register")
	@PermitAll
	@Blocking
	@Transactional
	public PollyJwtResponse register(@NotNull JsonObject webAuthnRegisterData, RoutingContext ctx) {
		String userHandle = readUserHandleCookie(ctx);
		if (userHandle == null) {
			throw new PollyException(PollyError.NEED_PASSKEY,
					"Missing polly registration handle. Did the cookie come back? (needs withCredentials and SameSite=None)");
		}

		WebAuthnCredentialRecord credentialRecord;
		try {
			credentialRecord = webAuthnSecurity.register(userHandle, webAuthnRegisterData, ctx).await().indefinitely();
		} catch (RuntimeException e) {
			throw registrationFailed(e);
		} finally {
			clearUserHandleCookie(ctx);
		}

		// WebAuthnSecurity.register() deliberately does NOT call WebAuthnUserProvider.store(),
		// so we persist here - into Polly's own table, with no UserEntity anywhere in sight.
		PollyCredentialEntity credential = new PollyCredentialEntity(credentialRecord, userHandle);
		credential.persist();
		log.info("Registered a new polly passkey {}", credential);

		return new PollyJwtResponse(jwtTokenUtils.generatePollyToken(credential.credentialId));
	}

	// ================================================================= LOGIN (assertion)

	/**
	 * Login options with an <b>empty</b> {@code allowCredentials}, so the browser offers whatever
	 * passkey it has for this domain and we never need to know who is asking.
	 */
	@GET
	@Path("/login-options-challenge")
	@PermitAll
	@Blocking
	public String loginOptions(RoutingContext ctx) {
		log.debug("Polly WebAuthn GET /login-options-challenge (usernameless)");
		var requestOptions = webAuthnSecurity.getLoginChallenge(null, ctx).await().indefinitely();
		return webAuthnSecurity.toJsonString(requestOptions);
	}

	/**
	 * Verify the assertion and hand back a polly session.
	 *
	 * <p>The credential may be one registered here, or one registered for a LIQUIDO team account
	 * on the same device - see {@code LiquidoWebAuthnSetup.findByCredentialId}. Either way the
	 * credential id is the identity, and that is all Polly needs.
	 */
	@POST
	@Path("/login")
	@PermitAll
	@Blocking
	@Transactional
	public PollyJwtResponse login(@NotNull JsonObject webAuthnLoginData, RoutingContext ctx) {
		WebAuthnCredentialRecord credentialRecord;
		try {
			credentialRecord = webAuthnSecurity.login(webAuthnLoginData, ctx).await().indefinitely();
		} catch (RuntimeException e) {
			throw loginFailed(e);
		}

		String credentialId = credentialRecord.getRequiredPersistedData().credentialId();

		// login() does not call WebAuthnUserProvider.update() either, so advance the replay
		// counter ourselves. Only for polly credentials; a team credential is not ours to touch.
		PollyCredentialEntity credential = PollyCredentialEntity.findByCredentialId(credentialId);
		if (credential != null) {
			credential.counter = credentialRecord.getRequiredPersistedData().counter();
			credential.lastUsed = LocalDateTime.now();
			credential.persist();
		}

		log.debug("Polly WebAuthn login succeeded");
		return new PollyJwtResponse(jwtTokenUtils.generatePollyToken(credentialId));
	}

	// ================================================================= helpers

	private void setUserHandleCookie(RoutingContext ctx, String userHandle) {
		ctx.response().addCookie(Cookie.cookie(USER_HANDLE_COOKIE, userHandle)
				.setPath("/polly/webauthn")
				.setHttpOnly(true)
				.setSecure(ctx.request().isSSL())
				.setSameSite(CookieSameSite.NONE)     // the frontend is a different origin than the API
				.setMaxAge(HANDLE_COOKIE_MAX_AGE_SECONDS));
	}

	private String readUserHandleCookie(RoutingContext ctx) {
		Cookie cookie = ctx.request().getCookie(USER_HANDLE_COOKIE);
		return cookie == null || cookie.getValue() == null || cookie.getValue().isBlank() ? null : cookie.getValue();
	}

	private void clearUserHandleCookie(RoutingContext ctx) {
		Cookie cookie = ctx.request().getCookie(USER_HANDLE_COOKIE);
		if (cookie != null) cookie.setPath("/polly/webauthn");
		ctx.response().removeCookie(USER_HANDLE_COOKIE);
	}

	private PollyException registrationFailed(RuntimeException e) {
		log.warn("Polly WebAuthn registration failed: {}", e.getMessage());
		return new PollyException(PollyError.NEED_PASSKEY, "Polly passkey registration failed: " + challengeHint(e));
	}

	private PollyException loginFailed(RuntimeException e) {
		log.warn("Polly WebAuthn login failed: {}", e.getMessage());
		return new PollyException(PollyError.NEED_PASSKEY, "Polly passkey login failed: " + challengeHint(e));
	}

	/** "Missing challenge" is nearly always a cookie that did not come back. Say so. */
	private String challengeHint(RuntimeException e) {
		String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		if (msg.contains("Missing challenge")) {
			return msg + " - the challenge cookie did not come back. Check that the frontend origin is in "
					+ "quarkus.webauthn.origins, that CORS allows credentials, and that "
					+ "quarkus.webauthn.cookie-same-site=None.";
		}
		return msg;
	}
}
