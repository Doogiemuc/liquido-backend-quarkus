package org.liquido.polly;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.*;

import java.util.List;

/**
 * The GraphQL API of the Polly module - a thin adapter over {@link PollyService}, which owns
 * every invariant.
 *
 * <h3>Why everything here is {@code @PermitAll}</h3>
 * Not an oversight. {@code @RolesAllowed} would raise Quarkus' {@code UnauthorizedException},
 * which {@code LiquidoErrorExtensionProvider} turns into a {@code liquidoException} extension -
 * and the frontend would then see no {@code pollyErrorCode} at all where it expects
 * NEED_PASSKEY. Authentication is therefore checked inside the service, in an order the
 * frontend's tests assert. {@code PollySession} additionally requires the
 * {@code LIQUIDO_POLLY} group, so a team JWT is never mistaken for a polly session.
 *
 * <p>Reading a polly genuinely is public: the share link has to work before anybody taps a
 * passkey.
 */
@Slf4j
@GraphQLApi
public class PollyGraphQL {

	@Inject
	PollyService pollyService;

	@Mutation
	@PermitAll
	@Description("Create a polly. It is open for voting immediately - there is no start step.")
	public PollyResponse createPolly(
			@Name("title") @NonNull String title,
			@Name("proposalTitles") @NonNull List<String> proposalTitles
	) {
		return pollyService.createPolly(title, proposalTitles);
	}

	@Query
	@PermitAll
	@Description("Read a polly by its public id. Works without a session, so the link works before any passkey tap.")
	public PollyResponse polly(@Name("publicId") @NonNull String publicId) {
		return pollyService.getPolly(publicId);
	}

	@Mutation
	@PermitAll
	@Description("Change the question or the options. Owner only, and only while nobody has voted.")
	public PollyResponse editPolly(
			@Name("publicId") @NonNull String publicId,
			@Name("title") @NonNull String title,
			@Name("proposalTitles") @NonNull List<String> proposalTitles
	) {
		return pollyService.editPolly(publicId, title, proposalTitles);
	}

	/**
	 * @param voteOrder proposal ids, favourite first.
	 *
	 * <p>Typed {@code [String!]!} rather than {@code [ID!]!}. MicroProfile's {@code @Id} cannot
	 * express a list of IDs here: SmallRye applies it to the outer type before unwrapping the
	 * collection, so {@code @Id List<String>} asks for an ID scalar named {@code java.util.List}
	 * and every call fails with "Unknown Scalar Type". Since GraphQL requires a variable's named
	 * type to match the argument's exactly, {@code polly-client.js} declares this variable as
	 * {@code [String!]!} to match. Values are unaffected - GraphQL serialises ID as a string.
	 */
	@Mutation
	@PermitAll
	@Description("Cast a ballot: the option ids in preferred order, favourite first. One ballot per passkey.")
	public PollyResponse voteInPolly(
			@Name("publicId") @NonNull String publicId,
			@Name("voteOrder") @NonNull List<String> voteOrder
	) {
		return pollyService.voteInPolly(publicId, voteOrder);
	}

	@Mutation
	@PermitAll
	@Description("Close the polly and calculate the winner with Ranked Pairs. Owner only.")
	public PollyResponse finishPolly(@Name("publicId") @NonNull String publicId) {
		return pollyService.finishPolly(publicId);
	}

	@Query
	@PermitAll
	@Description("Every polly this passkey created. Replaces the 'email me my link' step.")
	public List<PollyResponse> myPollys() {
		return pollyService.myPollys();
	}

	/**
	 * Mint a polly session without a passkey. <b>Development and testing only.</b>
	 *
	 * <p>A WebAuthn ceremony cannot be driven from a headless browser - there is no
	 * authenticator to talk to - so an automated end-to-end test can never get a polly session
	 * the normal way. This is the polly counterpart of {@code UserGraphQL.devLogin} and carries
	 * the same three guards: never in prod, never without a configured token, never with the
	 * wrong token.
	 *
	 * <p>The passkey ceremony itself therefore has to be verified by hand on a real device.
	 * What this lets the e2e suite cover is everything after it, which is all of the product.
	 *
	 * @param devLoginToken the secret {@code liquido.dev-login-token}
	 * @param credentialId stands in for a verified WebAuthn credential id. Different values are
	 *        different people, so a test can be several voters in one polly.
	 * @return a polly JWT, exactly as the real ceremony would have returned
	 */
	@Query
	@PermitAll
	@Description("Only for development: get a polly session without a passkey.")
	public String devLoginPolly(
			@Name("devLoginToken") @NonNull String devLoginToken,
			@Name("credentialId") @NonNull String credentialId
	) {
		return pollyService.devLoginPolly(devLoginToken, credentialId);
	}
}
