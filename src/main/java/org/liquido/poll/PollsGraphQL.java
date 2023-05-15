package org.liquido.poll;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.graphql.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoException;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.CastVoteResponse;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@GraphQLApi
public class PollsGraphQL {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@ConfigProperty(name = "liquido.durationOfVotingPhase")
	Long durationOfVotingPhase;

	/**
	 * Get one poll by its ID
	 * @param pollId pollId (mandatory)
	 * @return the PollEntity
	 */
	@Query
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public PollEntity poll(Long pollId) throws LiquidoException {
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
		PollEntity poll = new PollEntity(title);
		poll.setTeam(team);
		poll.persist();
		//TODO: createdBy is NULL here. Why?
		log.info("createPoll: Admin created new " + poll + " in " + team);
		return poll;
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
		UserEntity user = jwtTokenUtils.getCurrentUser().orElseThrow(LiquidoException.unauthorized("Must be logged in to add a proposal!"));

		// Find the poll
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId).orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposal: There is no poll with id="+pollId));

		// Poll must be in status ELABORATION and must not already contain a proposal with that title
		if (poll.getStatus() != PollEntity.PollStatus.ELABORATION)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposalToPoll: Poll(id="+poll.id+") is not in ELABORATION phase");
		if (poll.getProposals().stream().anyMatch(prop -> prop.title.equals(title)))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Poll.id="+poll.id+" already contains a proposal with the same title="+title);
		
		// Create a new proposal and add it to the poll
		ProposalEntity proposal = new ProposalEntity(title, description);
		proposal.setIcon(icon);
		proposal.setStatus(ProposalEntity.LawStatus.PROPOSAL);
		proposal.setPoll(poll);  // Don't forget to set both sides of the relation!
		proposal.persist();
		poll.getProposals().add(proposal);
		poll.persist();

		//poll = pollService.addProposalToPoll(proposal, poll);
		//BUGFIX: proposal.getCreatedBy() is not yet filled here
		log.info("addProposal: " + user.toStringShort() + " adds proposal '" + proposal.getTitle() + "' to poll.id="+poll.id);
		return poll;
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
	 * Get a valid voter Token for casting a ballot.
	 * @param tokenSecret secret that only the user must know!
	 * @param becomePublicProxy if user's automatically wants to become a public proxy (default=false)
	 * @return { "voterToken": "$2ADDgg33gva...." }
	 * @throws LiquidoException when user is not logged into a team
	 */
	@Query
	@Description("Get voter's the secret voterToken")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public String voterToken(
			@NonNull @Name("tokenSecret") String tokenSecret,
			@DefaultValue("false") Boolean becomePublicProxy
	) throws LiquidoException {
		UserEntity voter = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.unauthorized("Must be logged in to getVoterToken!"));
		//TODO: return castVoteService.createVoterTokenAndStoreRightToVote(voter, area, tokenSecret, becomePublicProxy);
		return null;
	}

	/**
	 * Is a proposal already liked by the currently logged in user?
	 *
	 * @param proposal GraphQL context: the ProposalEntity
	 * @return true, if currently logged in user is already a supporter of this proposal
	 */
	@Query
	@Description("Is a proposal already liked by the currently logged in user?")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public boolean isLikedByCurrentUser(ProposalEntity proposal) {
		// This adds the new boolean field "likedByCurrentUser" to the GraphQL representation of a proposal(ProposalEntity) that can now be queried by the client.  graphql-spqr I like
		Optional<UserEntity> voter = jwtTokenUtils.getCurrentUser();
		if (voter.isEmpty()) return false;
		return proposal.getSupporters().contains(voter.get());
	}

	/**
	 * Is a proposal created by the currently logged in user
	 * This of course assumes that there is a currently logged in user. But polls and propos can only be fetched by authenticated users.
	 *
	 * @param proposal A proposal in a poll.
	 * @return true if proposal was created by the currently logged in user.
	 */
	@Query
	@Description("Is a proposal created by the currently logged in user?")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public boolean isCreatedByCurrentUser(ProposalEntity proposal) {
		Optional<UserEntity> user = jwtTokenUtils.getCurrentUser();
		return proposal != null && user.isPresent() && user.get().equals(proposal.createdBy);
	}

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

		if (poll.getStatus() != PollEntity.PollStatus.ELABORATION)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_START_VOTING_PHASE, "Poll(id="+poll.id+") must be in status ELABORATION");
		if (poll.getProposals().size() < 2)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_START_VOTING_PHASE, "Poll(id="+poll.id+") must have at least two alternative proposals");

		for (ProposalEntity proposal : poll.getProposals()) {
			proposal.setStatus(ProposalEntity.LawStatus.VOTING);
		}
		poll.setStatus(PollEntity.PollStatus.VOTING);
		LocalDateTime votingStart = LocalDateTime.now();			// LocalDateTime is without a timezone
		poll.setVotingStartAt(votingStart);   //record the exact datetime when the voting phase started.
		poll.setVotingEndAt(votingStart.truncatedTo(ChronoUnit.DAYS).plusDays(durationOfVotingPhase));     //voting ends in n days at midnight
		poll.persist();
		return poll;
	}

	/**
	 * Cast a vote in a poll
	 * A user may overwrite his previous ballot as long as the poll is still in its VOTING phase.
	 * This request can be sent anonymously!
	 *
	 * @param pollId poll id that must exist
	 * @param voteOrderIds list of proposals IDs as sorted by the voter in his ballot
	 * @param voterToken a valid voter token
	 * @return CastVoteResponse
	 * @throws LiquidoException when poll.id ist not found, voterToken is invalid or voterOrder is empty.
	 */
	@Mutation
	@Description("Cast a vote in a poll with ballot")
	@Transactional
	public CastVoteResponse castVote(
			@NonNull long pollId,
			@NonNull List<Long> voteOrderIds,
			@NonNull String voterToken
	) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot cast vote. Poll(id="+pollId+") not found!"));
		//TODO: CastVoteResponse res = castVoteService.castVote(voterToken, poll, voteOrderIds);
		log.info("castVote: poll.id=" + pollId);		//TODO: log all user actions into seperate file
		return null; //TODO: res;
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
		//TODO: return pollService.finishVotingPhase(poll);
		return null;
	}

	/**
	 * Get the ballot of a voter in a poll, if the voter has already casted one.
	 * @param voterToken voter's secret voterToken
	 * @param pollId poll.id
	 * @return the voter's ballot if there is one
	 * @throws LiquidoException when voterToken is invalid
	 */
	@Query("ballot")
	@Description("Get the ballot of a voter in a poll, if the voter has already casted one.")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public Optional<BallotEntity> getBallot(
			@NonNull String voterToken,
			@NonNull long pollId
	) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot start voting phase. Poll(id="+pollId+") not found!"));
		//TODO: return pollService.getBallotForVoterToken(poll, voterToken);
		return null;
	}

	/**
	 * Verify a voter's ballot with its checksum. When the checksum is valid, the
	 * ballot with the correct voteOrder will be returned.
	 * @param pollId a poll
	 * @param checksum checksum of a ballot in that poll
	 * @return the voter's ballot if it matches the checksum.
	 * @throws LiquidoException when poll cannot be found
	 */
	@Query
	@Description("Verify a ballot with its checksum.")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public Optional<BallotEntity> verifyBallot(
			@NonNull long pollId,
			@NonNull String checksum
	) throws LiquidoException {
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot verify checksum! Poll(id="+pollId+") not found!"));
		//TODO: return pollService.getBallotForChecksum(poll, checksum);
		return null;
	}
}
