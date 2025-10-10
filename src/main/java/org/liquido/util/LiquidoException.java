package org.liquido.util;


import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Supplier;

/**
 * <h1>LiquidoException</h1>
 *
 * LiquidoException is the one central place for handling exceptions in the application.
 * There are two kind of exceptions:
 * <ul>
 * <li>Severe system error. Things that should never happen.</li>
 * <li>And normal business exceptions. For example when a poll cannot be finished, because there are no votes yet.</li>
 * </ul>
 * When a LiquidoException is thrown, then this is handled in the {@link LiquidoErrorExtensionProvider}. There
 * the errorName and errorCode are added to the GraphQL extensions JSON field:
 *
 * <h3>Example GraphQL response with errors</h3>
 *
 * There might still be data for some attributes, while there are errors for other attributes.
 *
 * <pre>
 * {
 *   "data": {
 *     "requestEmailToken": null
 *   },
 *   "errors": [
 *     {
 *       "message": "Cannot login. No user with that email.",
 *       "locations": [
 *         {
 *           "line": 1,
 *           "column": 9
 *         }
 *       ],
 *       "path": [
 *         "requestEmailToken"
 *       ],
 *       "extensions": {
 *         "liquidoException": {
 *           "liquidoErrorName": "CANNOT_LOGIN_EMAIL_NOT_FOUND",
 *           "liquidoErrorCode": 21,
 *           "liquidoErrorMessage": "I don't know any user with email <werfsd@xn--fdsasdffd-nzb.de>"
 *         }
 *       }
 *     }
 *   ]
 * }
 * </pre>
 */
@Slf4j
public class LiquidoException extends Exception {

	/** LIQUIDO error code */
	Errors error;

	/**
	 * Additional key/value payload that can be added to the exception.
	 * Do not add sensitive data to this!!!
	 * This data will be serialized to the resulting JSON at will be returned to the client.
	 */
	Map payload;

	/**
	 * These codes are pretty fine-grained. The idea here is that the client can show
	 * usefull and localized messages to a human depending on these codes.
	 */
	public enum Errors {
		CANNOT_REGISTER_NEED_EMAIL(1, Response.Status.BAD_REQUEST),
		CANNOT_REGISTER_NEED_MOBILEPHONE(2, Response.Status.BAD_REQUEST),

		// Create New Team
		TEAM_WITH_SAME_NAME_EXISTS(10, Response.Status.CONFLICT),
		CANNOT_CREATE_TEAM_ALREADY_REGISTERED(11, Response.Status.CONFLICT),      // Edge case: When a user is already registered and want's to create a team, ...
		// Join a team
		CANNOT_JOIN_TEAM_INVITE_CODE_INVALID(12, Response.Status.BAD_REQUEST),
		CANNOT_JOIN_TEAM_ALREADY_MEMBER(13, Response.Status.CONFLICT),            // there already is a member (or admin) with the same email or mobilephone
		CANNOT_JOIN_TEAM_ALREADY_ADMIN(14, Response.Status.CONFLICT),
		CANNOT_CREATE_TWILIO_USER(15, Response.Status.INTERNAL_SERVER_ERROR),
		USER_EMAIL_EXISTS(16, Response.Status.CONFLICT),                         // user with that email already exists
		USER_MOBILEPHONE_EXISTS(17, Response.Status.CONFLICT),                   // user with that mobile phone already exists

		//Login Errors
		CANNOT_LOGIN_MOBILE_NOT_FOUND(20, Response.Status.UNAUTHORIZED),          // when requesting an SMS login token and mobile number is not known
		CANNOT_LOGIN_EMAIL_NOT_FOUND(21, Response.Status.UNAUTHORIZED),          // when requesting a login email and email is not known
		CANNOT_LOGIN_TOKEN_INVALID(22, Response.Status.UNAUTHORIZED),            // when a email or sms login token is invalid or expired
		CANNOT_LOGIN_TEAM_NOT_FOUND(23, Response.Status.UNAUTHORIZED),           // when changing team
		CANNOT_LOGIN_USER_NOT_MEMBER_OF_TEAM(24, Response.Status.UNAUTHORIZED),  // when changing team and user is not member or admin of target team
		CANNOT_LOGIN_INTERNAL_ERROR(25, Response.Status.INTERNAL_SERVER_ERROR),  // when sending of email is not possible
		CANNOT_REQUEST_SMS_TOKEN(26, Response.Status.UNAUTHORIZED),              // eg. when entered mobile number is not valid

		//JWT Errors  // these are now handled by Quarkus
		JWT_TOKEN_INVALID(30, Response.Status.UNAUTHORIZED),
		JWT_TOKEN_EXPIRED(31, Response.Status.UNAUTHORIZED),

		// use case errors
		INVALID_VOTER_TOKEN(50, Response.Status.UNAUTHORIZED),
		CANNOT_CREATE_POLL(51, Response.Status.BAD_REQUEST),
		CANNOT_JOIN_POLL(52, Response.Status.BAD_REQUEST),
		CANNOT_ADD_PROPOSAL(53, Response.Status.BAD_REQUEST),
		CANNOT_START_VOTING_PHASE(54, Response.Status.BAD_REQUEST),
		CANNOT_SAVE_PROXY(55, Response.Status.BAD_REQUEST),                // assign or remove
		CANNOT_ASSIGN_CIRCULAR_PROXY(56, Response.Status.BAD_REQUEST),
		CANNOT_CAST_VOTE(57, Response.Status.BAD_REQUEST),
		CANNOT_GET_TOKEN(58, Response.Status.BAD_REQUEST),
		CANNOT_FINISH_POLL(59, Response.Status.BAD_REQUEST),
		NO_DELEGATION(60, Response.Status.BAD_REQUEST),
		NO_BALLOT(61, Response.Status.NO_CONTENT),                          // 204: voter has no ballot yet. This is OK and not an error.
		INVALID_POLL_STATUS(62, Response.Status.BAD_REQUEST),
		PUBLIC_CHECKSUM_NOT_FOUND(63, Response.Status.NOT_FOUND),
		CANNOT_ADD_SUPPORTER(64, Response.Status.BAD_REQUEST),              // e.g. when user tries to support his own proposal

		CANNOT_CALCULATE_UNIQUE_RANKED_PAIR_WINNER(70, Response.Status.INTERNAL_SERVER_ERROR),    // this is only used in the exceptional situation, that no unique winner can be calculated in RankedPairVoting
		CANNOT_VERIFY_CHECKSUM(80, Response.Status.NOT_FOUND),              // ballot's checksum could not be verified

		// general errors
		GRAPHQL_ERROR(400, Response.Status.BAD_REQUEST),                     // e.g. missing required fields, invalid GraphQL query, ...
		UNAUTHORIZED(401, Response.Status.UNAUTHORIZED),                     // when client tries to call something without being authenticated!
		CANNOT_FIND_ENTITY(404, Response.Status.NOT_FOUND),                  // 404: cannot find entity
		INTERNAL_ERROR(500, Response.Status.INTERNAL_SERVER_ERROR);


		final int liquidoErrorCode;
		final Response.Status httpResponseStatus;

		Errors(int code, Response.Status httpResponseStatus) {
			this.liquidoErrorCode = code;
			this.httpResponseStatus = httpResponseStatus;
		}

		int getLiquidoErrorCode() {
			return this.liquidoErrorCode;
		}

		Response.Status getHttpResponseStatus() {
			return this.httpResponseStatus;
		}

	}

	/**
	 * A Liquido exception must always have an error code and a human-readable error message
	 */
	public LiquidoException(Errors errCode, String msg) {
		super(msg);
		this.error = errCode;
	}

	public LiquidoException(Errors errCode, String msg, Throwable childException) {
		super(msg, childException);
		this.error = errCode;
	}

	public LiquidoException(Errors errCode, String msg, Throwable childException, Map payload) {
		this(errCode, msg, childException);
		this.payload = payload;
	}


	/**
	 * This utility method be passed to java.util.Optional methods, e.g.
	 * <pre>Optional.orElseThrow(LiquidoException.notFound("not found"))</pre>
	 * @param msg The human-readable error message
	 * @return a Supplier for that LiquidoExeption that can be passed to java.util.Optional methods.
	 */
	public static Supplier<LiquidoException> notFound(String msg) {
		return () -> new LiquidoException(Errors.CANNOT_FIND_ENTITY, msg);
	}

	public static Supplier<LiquidoException> unauthorized(String msg) {
		return () -> new LiquidoException(Errors.UNAUTHORIZED, msg);
	}

	/**
	 * Supply an exception. This can be used in Optional methods, e.g.
	 * <pre>Optional.orElseThrow(LiquidoException.supply(LiquidoException.SOME_NAME, "Some message"))</pre>
	 * @param error Liquido Error Code
	 * @param msg Humand-readable error message
	 * @return a Supplier for the LiquidoException
	 */
	public static Supplier<LiquidoException> supply(Errors error, String msg) {
		return () -> new LiquidoException(error, msg);
	}

	public static Supplier<LiquidoException> supplyAndLog(Errors error, String msg) {
		if (error.httpResponseStatus.getStatusCode() >= 500) {
			log.error(error.name() + ": " + msg);
		} else {
			log.info(error.name() + ": " + msg);
		}
		return () -> new LiquidoException(error, msg);
	}

	public Errors getError() {
		return this.error;
	}

	public int getErrorCodeAsInt() {
		return this.error.liquidoErrorCode;
	}

	public String getErrorName() {
		return this.error.name();
	}

	public Response.Status getHttpResponseStatus() {
		return this.error.httpResponseStatus;
	}

	//TODO: maybe use https://github.com/quarkusio/qson
	/*
	public Lson toLson() {
		Lson lson = Lson.builder()
			.put("exception", this.getClass().toString())
			.put("message", this.getMessage())
			.put("liquidoErrorCode", this.getErrorCodeAsInt())
			.put("liquidoErrorName", this.getErrorName())
			.put("Response.StatusCode", this.getHttpResponseStatus().getStatusCode())
		  .put("Response.StatusName", this.getHttpResponseStatus().getReasonPhrase());

		if (this.getCause() != null) lson.put("cause", this.getCause().toString());
		if (this.payload != null && payload.size() > 0) lson.put("liquidoErrorPayload", this.payload);

		return lson;
	}

	 */

	public String toString() {
		StringBuilder b = new StringBuilder("LiquidoException[");
		b.append("liquidoErrorCode=");
		b.append(this.getErrorCodeAsInt());
		b.append(", errorName=");
		b.append(this.getErrorName());
		b.append(", msg=");
		b.append(this.getMessage());
		if (this.getCause() != null) {
			b.append(", cause=");
			b.append(this.getCause().toString());
		}
		b.append("]");
		return b.toString();
	}
}