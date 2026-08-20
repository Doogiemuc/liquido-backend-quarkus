package org.liquido.util;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;

/**
 * <h1>Settings the frontend must agree with the backend on</h1>
 *
 * The frontend used to keep its own copies of these in {@code config/config.common.js}, next to a
 * {@code //TODO: implement these settings per Team! in the backend!}. Two sources of truth for one
 * rule is a bug waiting to happen, and it had already happened:
 * {@code proposalDescriptionMinLength} read 10 in the frontend while {@code ProposalEntity} enforces
 * {@code @Size(min = 20)}, so a description of 12 characters passed client validation and was then
 * refused by the server with no useful explanation.
 *
 * <h2>Why a separate query and not part of ping</h2>
 *
 * {@code UserGraphQL.pingApi()} deliberately returns a plain String and says so on the method
 * ("Keep this very simple! Just return a string!"). It is the liveness check; turning it into an
 * object would change that contract for every existing caller. The frontend fetches this alongside
 * the ping at startup instead.
 *
 * <h2>Anonymous on purpose</h2>
 *
 * {@code @PermitAll}: the welcome, join and login screens need these rules (nickname length, invite
 * code length, password length) before anybody is logged in. Nothing here is secret - they are the
 * same rules the UI would reveal anyway by rejecting your input.
 */
@Slf4j
@GraphQLApi
public class ConfigGraphQL {

	@Inject
	LiquidoConfig config;

	@Query("liquidoConfig")
	@Description("Validation rules and limits that the frontend shares with the backend")
	@PermitAll
	public LiquidoConfigResponse liquidoConfig() {
		return new LiquidoConfigResponse(
				config.usernameMinLength(),
				config.inviteCodeLength(),
				config.minPasswordLength(),
				config.allowMembersToInvite(),
				config.pollTitleMinLength(),
				config.durationOfVotingPhase(),
				config.proposalTitleMinLength(),
				config.proposalDescriptionMinLength(),
				config.frontendUrl() + config.inviteLinkPath()
		);
	}
}
