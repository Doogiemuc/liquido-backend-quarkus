package org.liquido.poll;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.CastVoteResponse;
import org.liquido.vote.CastVoteService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * This adapter only handles the GraphQL API specifics.
 * All the business logic is in PollService.
 */
@Slf4j
@GraphQLApi
public class PollsGraphQL {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	PollService pollService;

	@Inject
	CastVoteService castVoteService;

	@Inject
	LiquidoConfig config;

	/**
	 * Get one poll by its ID
	 * @param pollId pollId (mandatory)
	 * @return the PollEntity
	 */
	@Query
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public PollEntity poll(@NonNull Long pollId) throws LiquidoException {
		Optional<PollEntity> pollOpt = PollEntity.findByIdOptional(pollId);
		return pollOpt.orElseThrow(LiquidoException.notFound("Poll.id=" + pollId + " not found."));
	}

	/**
	 * Get all polls of currently logged in user's team.
	 * @return Set of polls
	 * @throws LiquidoException when no one is logged in.
	 */
	@Query
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public Set<PollEntity> polls() throws LiquidoException {
		TeamEntity team = jwtTokenUtils.getCurrentTeam()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Cannot get polls of team: Must be logged into a team!"));
		return team.getPolls();
	}

	/**
	 * Admin of a team creates a new poll.
	 * The VOTING phase of this poll will be started manually by the admin later.
	 * @param title title of poll
	 * @return the newly created poll
	 */
	@Mutation
	@Description("Admin creates a new poll")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_ADMIN_ROLE)
	@Transactional
	public PollEntity createPoll(
			@NonNull String title
	) throws LiquidoException {
		TeamEntity team = jwtTokenUtils.getCurrentTeam()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Cannot create poll: Must be logged into a team!"));
		return pollService.createPoll(title, team);
	}

	/**
	 * Add a proposal to a poll. A user is only allowed to have one proposal in each poll.
	 * and all proposals in a poll must of course have different titles.
	 * 
	 * @param pollId the poll, MUST exist
	 * @param title Title of the new proposal. MUST be unique within the poll.
	 * @param description Longer description of the proposal
	 * @return The updated poll with the added proposal
	 * @throws LiquidoException when proposal title already exists in this poll.
	 */
	@Mutation
	@Description("Add a new proposal to a poll")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Transactional
	public PollEntity addProposal(
			@NonNull long pollId,
			@NonNull String title,
			@NonNull String description,
			@NonNull String icon
	) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId).orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposal: There is no poll with id="+pollId));
		ProposalEntity proposal = new ProposalEntity(title, description);
		proposal.setIcon(icon);
		proposal.setStatus(ProposalEntity.LawStatus.PROPOSAL);
		return pollService.addProposalToPoll(proposal, poll);
	}

	/**
	 * Like a proposal in a poll.
	 * Poll must be in status ELABORATION
	 *
	 * @param pollId a poll
	 * @param proposalId a proposal of that poll
	 * @return the poll
	 * @throws LiquidoException when poll is not in status ELABORATION or when passed proposalId is not part of that poll.
	 */
	@Mutation
	@Description("Add a like to a proposal in a poll")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Transactional
	public PollEntity likeProposal(
			@NonNull long pollId,
			@NonNull long proposalId
	) throws LiquidoException {
		UserEntity user = jwtTokenUtils.getCurrentUser().orElseThrow(LiquidoException.unauthorized("Must be logged in to like a proposal!"));

		// Find the poll and check that poll is in status ELABORATION
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ADD_SUPPORTER, "Cannot supportProposal: There is no poll with id=" + pollId));
		if (!poll.getStatus().equals(PollEntity.PollStatus.ELABORATION))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_SUPPORTER, "Cannot supportProposal: Poll is not in status ELABORATION");

		// Find the proposal in this poll
		ProposalEntity proposal = poll.getProposals().stream().filter(prop -> prop.id == proposalId).findFirst()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot supportProposal: There is no proposal,id=" + proposalId + " in poll.id=" + pollId));

		proposal.getSupporters().add(user);
		proposal.persist();

		log.info("likeProposal: " + user.toStringShort() + " likes proposal.id=" + proposalId + " in poll.id="+poll.id);
		return poll;
	}

	/**
	 * Is a proposal already liked by the currently logged in user?
	 *
	 * @param proposal GraphQL context: the ProposalEntity
	 * @return true, if currently logged in user is already a supporter of this proposal
	 */
	@Query
	@Description("Is a proposal already liked by the currently logged in user?")
	public boolean isLikedByCurrentUser(@Source(name = "isLikedByCurrentUser")
																				@Description("Is a proposal already liked by the currently logged in user?")
																				ProposalEntity proposal) {
		Optional<UserEntity> voter = jwtTokenUtils.getCurrentUser();
		if (voter.isEmpty()) return false;
		return proposal.getSupporters().contains(voter.get());
	}

	/**
	 * Is a proposal created by the currently logged in user
	 * This of course assumes that there is a currently logged in user. But polls and proposals can only be fetched by authenticated users.
	 *
	 * @param proposal A proposal in a poll.
	 * @return true if proposal was created by the currently logged in user.
	 */
	@Query
	@Description("Is a proposal created by the currently logged in user?")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public boolean isCreatedByCurrentUser(@Source ProposalEntity proposal) {
		Optional<UserEntity> user = jwtTokenUtils.getCurrentUser();
		return proposal != null && user.isPresent() && user.get().equals(proposal.createdBy);
	}

	//Reminder: It is not possible to check if a user has already voted in a poll. Pools and ballots are not linked via username! Only via hashedVoterTokens

	/**
	 * Start the voting Phase of a poll
	 * @param pollId poll.id
	 * @return the poll in VOTING
	 * @throws LiquidoException when voting phase cannot yet be started
	 */
	@Mutation
	@Description("Start voting phase of a poll")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_ADMIN_ROLE)
	@Transactional
	public PollEntity startVotingPhase(@NonNull long pollId) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot start voting phase. Poll(id="+pollId+") not found!"));
		return pollService.startVotingPhase(poll);
	}

	/**
	 * Get a voter token for casting a ballot in this poll.
	 *
	 * @param pollId ID of the poll
	 * @param becomePublicProxy if user's automatically wants to become a public proxy (default=false)
	 * @return { "voterToken": "$2ADDgg33gva...." }
	 * @throws LiquidoException when user is not logged into a team
	 */
	@Query
	@Description("Get a voter token to cast a vote in this poll.")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public String voterToken(
			@NonNull Long pollId,
			@DefaultValue("false") Boolean becomePublicProxy
	) throws LiquidoException {
		UserEntity voter = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.unauthorized("Must be logged in to getVoterToken!"));
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot get voterToken. poll.id=" + pollId + " not found!"));
		return castVoteService.createVoterToken(voter, poll, becomePublicProxy);
	}

	/**
	 * Cast a vote in a poll
	 * A user may overwrite his previous ballot as long as the poll is still in its VOTING phase.
	 * <b>This request can be sent anonymously!</b>
	 *
	 * @param pollId poll id that must exist
	 * @param voteOrderIds list of proposals IDs as sorted by the voter in his ballot
	 * @param voterToken a valid voter token
	 * @return CastVoteResponse
	 * @throws LiquidoException when poll.id ist not found, voterToken is invalid or voterOrder is empty.
	 */
	@Mutation
	@Description("Cast a vote in a poll. With the proposals as sorted by the user.")
	// casting a vote can be called anonymously!!! The anonymous voter is only validated by the voteToken
	@Transactional
	public CastVoteResponse castVote(
			@NonNull long pollId,
			@NonNull List<Long> voteOrderIds,   // Must be passed as [BigInteger!]!  in GraphQL
			@Description("The plain voter token that the voter has received for this poll.")
			@NonNull String voterToken
	) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot cast vote. Poll(id="+pollId+") not found!"));
		CastVoteResponse res = castVoteService.castVote(voterToken, poll, voteOrderIds);
		log.info("castVote: poll.id=" + pollId);		//TODO: log all user actions into separate file or even better into some business process data mining analytics tool. (buzzword bingo)
		return res;
	}

	/**
	 * Finish the voting phase of a poll
	 * @param pollId poll.id
	 * @return the winning law
	 * @throws LiquidoException when poll is not in status voting
	 */
	@Mutation
	@Description("Finish voting phase of a poll")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_ADMIN_ROLE)
	@Transactional
	public ProposalEntity finishVotingPhase(@NonNull long pollId) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot start voting phase. Poll(id="+pollId+") not found!"));
		return pollService.finishVotingPhase(poll);
	}


	/**
	 * Get the ballot of a voter in a poll, if the voter has already cast one.
	 * @param pollId poll.id
	 * @return the voter's ballot if there is one
	 * @throws LiquidoException when voterToken is invalid
   */
	@Query("myBallot")
	@Description("Get the ballot of a voter in a poll, if the voter has already casted one.")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public Optional<BallotEntity> getBallotOfCurrentUser(
			@NonNull long pollId
	) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot get Ballot for voterToken. Poll(id="+pollId+") not found!"));
		return pollService.getBallotOfCurrentUser(poll);
	}



	/**
	 * Anonymously verify that a ballot with this checksum has been cast and was counted correctly.
	 * When the checksum is valid, the ballot with its voteOrder will be returned.
	 * This can be called anonymously.
	 *
	 * @param pollId a poll
	 * @param checksum checksum of a ballot in that poll
	 * @return the voter's ballot if it matches the checksum.
	 * @throws LiquidoException when that poll cannot be found
	 */
	@Query
	@Description("Verify that a voter's ballot was counted correctly")
	public BallotEntity verifyBallot(
			@NonNull long pollId,
			@NonNull String checksum
	) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot verify checksum. Poll(id="+pollId+") not found!"));
		return BallotEntity.findByPollAndChecksum(poll, checksum)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_VERIFY_CHECKSUM, "No known ballot for that checksum."));
	}

	
}