package org.liquido.services;

import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.verify.v2.service.entity.Challenge;
import com.twilio.rest.verify.v2.service.entity.Factor;
import com.twilio.rest.verify.v2.service.entity.NewFactor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Client for the twilio Verify API.
 * https://www.twilio.com/docs/verify/
 */
@Slf4j
@ApplicationScoped
public class TwilioVerifyClient {
	/**
	 Registration flow:

	 1. Create a new UserEntity (join or create a team)
	 2. Create a twilio authentication Factor
	 3. Show a QR code to the user

	 */

	@Inject
	LiquidoConfig config;

	/**
	 * Register a new user. This will create an auth Factor
	 * and store the factor.uri in the user entity.
	 * <p>
	 * https://www.twilio.com/docs/verify/quickstarts/totp#create-a-new-totp-factor
	 *
	 * @param newUser newly created user
	 * @return
	 */
	public @NonNull UserEntity createFactor(@NonNull UserEntity newUser) throws LiquidoException {
		Twilio.init(config.twilio().accountSid(), config.twilio().authToken());

		if (newUser.id <= 0 || newUser.email == null)
			throw new LiquidoException(LiquidoException.Errors.INTERNAL_ERROR, "Cannot create new Factor. Need user.id and user.email!");

		try {
			// HEX representation of user's email (first 16 bytes = 32 hex chars) Needed for Twilio API. Must be between 8 and 64 characters.
			String identityHex = HexFormat.of().formatHex(newUser.getEmail().getBytes(StandardCharsets.UTF_8),0, 16);
			// may throw TwilioException extends RuntimeException
			NewFactor newFactor = NewFactor.creator(
							config.twilio().serviceSid(),
							identityHex,
							newUser.getEmail(),
							NewFactor.FactorTypes.TOTP)
					.setConfigAppId("org.liquido")
					.setConfigCodeLength(6)
					//.setConfigSkew(1)
					//.setConfigTimeStep(60)
					.create();

			String factorUri = (String) newFactor.getBinding().get("uri");
			String factorSid = newFactor.getSid();

			newUser.setTotpFactorUri(factorUri);
			newUser.setTotpFactorSid(factorSid);
			newUser.persist();
			log.info("Successfully registered auth factor for " + newUser.toStringShort());
			return newUser;
		} catch (ApiException e) {
			log.error("Twilio API Error: Cannot create new auth Factor. " + e.getMessage());
			throw e;
		}
	}

	/**
	 * Before the factor can be used it must be validated once.
	 * @param user newly registered user
	 * @param authToken 6-digit code from Authy app
	 * @return true if authToken was valid and factor is VERIFIED.
	 */
	public boolean verifyFactor(@NonNull UserEntity user, @NonNull String authToken) {
		String identityHex = HexFormat.of().formatHex(user.getEmail().getBytes(StandardCharsets.UTF_8),0, 16);
		Factor factor = Factor.updater(
						config.twilio().serviceSid(),
						identityHex,
						user.totpFactorSid)
				.setAuthPayload(authToken)
				.update();
		boolean verified = factor.getStatus().equals(Factor.FactorStatuses.VERIFIED);
		if (!verified) log.info("[SECURITY] Cannot verify factor! User provided wrong authToken " + user.toStringShort());
		return verified;
	}

	/**
	 * Login with authToken
	 * @param user the already registerd user
	 * @param authToken 6-digit token from Authy app
	 * @return true if authToken is valid. false if login is denied.
	 */
	public boolean loginWithAuthyToken(@NonNull UserEntity user, @NonNull String authToken) {
		String identityHex = HexFormat.of().formatHex(user.getEmail().getBytes(StandardCharsets.UTF_8),0, 16);
		Challenge challenge = Challenge.creator(
						config.twilio().serviceSid(),
						identityHex,
						user.totpFactorSid)
				.setAuthPayload(authToken).create();
		boolean approved = challenge.getStatus().equals(Challenge.ChallengeStatuses.APPROVED);
		if (!approved) log.info("[SECURITY] Cannot login! User provided wrong authToken " + user.toStringShort());
		return approved;
	}

}