package org.liquido.util;

import org.eclipse.microprofile.graphql.Description;

/**
 * The settings {@link ConfigGraphQL} hands to the frontend.
 *
 * Field names deliberately match the keys the frontend already used in {@code config.common.js}, so
 * the fetched values can simply be merged over the local defaults with no translation layer.
 *
 * Note two of them are renamed relative to {@link LiquidoConfig}: {@code pollDefaultRuntimeDays} is
 * the backend's {@code durationOfVotingPhase}, and {@code inviteLinkPrefix} is
 * {@code frontendUrl + inviteLinkPath} already joined, because the frontend only ever appends the
 * raw invite code to it.
 */
@Description("Validation rules and limits shared between the LIQUIDO frontend and backend")
public record LiquidoConfigResponse(
		@Description("Minimum length of a user's nickname")
		int usernameMinLength,

		@Description("Exact length of a team invite code")
		int inviteCodeLength,

		@Description("Minimum length of a password")
		int minPasswordLength,

		@Description("Minimum length of a poll title")
		int pollTitleMinLength,

		@Description("How many days a poll runs when the admin does not choose otherwise")
		int pollDefaultRuntimeDays,

		@Description("Minimum length of a proposal title")
		int proposalTitleMinLength,

		@Description("Minimum length of a proposal description. Matches ProposalEntity's @Size(min)")
		int proposalDescriptionMinLength,

		@Description("Full prefix an invite link is built from; the raw invite code is appended")
		String inviteLinkPrefix
) {}
