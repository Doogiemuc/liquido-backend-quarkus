package org.liquido.poll;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
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

	/**
	 * Get one poll by its ID
	 * @param pollId pollId (mandatory)
	 * @return the PollEntity
	 */
	@Query
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public PollEntity poll(@NonNull Long pollId) throws LiquidoException {
		return pollService.getPollInCurrentTeam(pollId);
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
	 *
	 * @param title title of poll
	 * @param membersCanAddProposals optional. When true, every team member may add proposals to this
	 *                               poll. When omitted or false, only the admin may - the default.
	 *                               Deliberately nullable so existing callers keep working.
	 * @return the newly created poll
	 */
	@Mutation
	@Description("Admin creates a new poll")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_ADMIN_ROLE)
	@Transactional
	public PollEntity createPoll(
			@NonNull String title,
			@Name("membersCanAddProposals") @Description("Optional: let team members add proposals to this poll. Default: only the admin may.") Boolean membersCanAddProposals
	) throws LiquidoException {
		TeamEntity team = jwtTokenUtils.getCurrentTeam()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Cannot create poll: Must be logged into a team!"));
		return pollService.createPoll(title, team, membersCanAddProposals);
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
		PollEntity poll = pollService.getPollInCurrentTeam(pollId);
		ProposalEntity proposal = new ProposalEntity(title, description);
		proposal.setIcon(icon);
		proposal.setStatus(ProposalEntity.LawStatus.PROPOSAL);
		return pollService.addProposalToPoll(proposal, poll);
	}

	/**
	 * Edit your <b>own</b> proposal, as long as the poll has not started yet.
	 *
	 * All the rules live in {@link PollService#updateProposalInPoll} - notably that you may only edit
	 * a proposal you created yourself, and only while the poll is still in ELABORATION.
	 *
	 * @param pollId the poll containing the proposal
	 * @param proposalId the proposal to edit. MUST be part of that poll, and created by the caller.
	 * @param title new title. MUST be unique within the poll.
	 * @param description new description
	 * @param icon new fontawesome icon name
	 * @return The updated poll
	 * @throws LiquidoException when the poll has started, the proposal is not yours, or the title is taken
	 */
	@Mutation
	@Description("Edit your own proposal, as long as the poll has not started yet")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Transactional
	public PollEntity updateProposal(
			@NonNull long pollId,
			@NonNull long proposalId,
			@NonNull String title,
			@NonNull String description,
			@NonNull String icon
	) throws LiquidoException {
		PollEntity poll = pollService.getPollInCurrentTeam(pollId);   // this is the team-scoping guard
		return pollService.updateProposalInPoll(poll, proposalId, title, description, icon);
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
		PollEntity poll = pollService.getPollInCurrentTeam(pollId);
		if (!poll.getStatus().equals(PollEntity.PollStatus.ELABORATION))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_SUPPORTER, "Cannot supportProposal: Poll is not in status ELABORATION");

		// Find the proposal in this poll
		ProposalEntity proposal = poll.getProposals().stream().filter(prop -> prop.id == proposalId).findFirst()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot supportProposal: There is no proposal,id=" + proposalId + " in poll.id=" + pollId));

		proposal.getSupporters().add(user);
		proposal.persist();

		log.info("likeProposal: {} likes proposal.id={} in poll.id={}", user.toStringShort(), proposalId, poll.id);
		return poll;
	}

	/**
	 * Is a proposal already liked by the currently logged in user?
	 *
	 * Deliberately no @Query -- see the note on numBallots below. @Source alone makes this a FIELD on
	 * ProposalEntity, which is the only way it is meant to be used.
	 *
	 * @param proposal GraphQL context: the ProposalEntity
	 * @return true, if currently logged in user is already a supporter of this proposal
	 */
	@Description("Is a proposal already liked by the currently logged in user?")
	public boolean isLikedByCurrentUser(@Source(name = "isLikedByCurrentUser")
																				@Description("Is a proposal already liked by the currently logged in user?")
																				ProposalEntity proposal) {
		Optional<UserEntity> voter = jwtTokenUtils.getCurrentUser();
		if (voter.isEmpty()) return false;
		return proposal.getSupporters().contains(voter.get());
	}

	/**
	 * Is a proposal created by the currently logged-in user
	 * This of course assumes that there is a currently logged-in user. But polls and proposals can only be fetched by authenticated users.
	 *
	 * Deliberately no @Query -- see the note on numBallots below.
	 *
	 * @param proposal A proposal in a poll.
	 * @return true if proposal was created by the currently logged-in user.
	 */
	@Description("Is a proposal created by the currently logged in user?")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public boolean isCreatedByCurrentUser(@Source ProposalEntity proposal) {
		Optional<UserEntity> user = jwtTokenUtils.getCurrentUser();
		return proposal != null && user.isPresent() && user.get().equals(proposal.createdBy);
	}

	/**
	 * Has the current user already voted in this poll?
	 * Only computed when the GraphQL client requests this field.
	 *
	 * Deliberately no @Query -- see the note on numBallots below.
	 *
	 * @param poll GraphQL context: the PollEntity
	 * @return true if the current user has a ballot in this poll
	 */
	@Description("Has the current user already voted in this poll?")
	public boolean userAlreadyVoted(@Source PollEntity poll) {
		try {
			return pollService.getBallotOfCurrentUser(poll).isPresent();
		} catch (LiquidoException e) {
			return false; // poll in ELABORATION has no ballots — not an error
		}
	}

	/**
	 * How many ballots have been cast in this poll so far?
	 * Only computed when the GraphQL client requests this field.
	 *
	 * This lives here rather than as a getter on PollEntity on purpose: SmallRye resolves plain entity
	 * getters on the IO/event-loop thread, so a getter that queries the DB fails with
	 * BlockingOperationNotAllowedException on every request. A @Source resolver in a @GraphQLApi bean
	 * runs on a worker thread, same as userAlreadyVoted() above. PollEntity carries a comment saying
	 * not to reintroduce such a getter.
	 *
	 * <h3>Why there is no @Query here</h3>
	 *
	 * {@code @Source} alone is what makes this a <b>field on PollEntity</b> - {@code poll { numBallots }} -
	 * computed only when a client actually selects it. Adding {@code @Query} on top would <i>additionally</i>
	 * publish it as a <b>root query</b>, {@code numBallots(poll: PollEntityInput)}, letting a caller pass a
	 * hand-assembled poll object that never came from the database. That is API surface nobody wants and
	 * nobody uses. All four @Source resolvers in this class deliberately omit @Query for the same reason.
	 *
	 * (Careful when reading the schema: SmallRye strips an {@code is} prefix, so
	 * {@code isLikedByCurrentUser} appears as the field {@code likedByCurrentUser}.)
	 *
	 * @param poll GraphQL context: the PollEntity
	 * @return number of ballots cast in this poll
	 */
	@Description("Number of ballots that have been cast in this poll so far")
	public long numBallots(@Source PollEntity poll) {
		return BallotEntity.count("poll", poll);
	}

	/**
	 * Start the voting Phase of a poll
	 * @param pollId poll.id
	 * @param durationInDays (optional) how many days the voting phase shall run. Falls back to the
	 *        configured default when not given, which is what every earlier client did.
	 * @return the poll in VOTING
	 * @throws LiquidoException when voting phase cannot yet be started
	 */
	@Mutation
	@Description("Start voting phase of a poll")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_ADMIN_ROLE)
	@Transactional
	public PollEntity startVotingPhase(
			@NonNull long pollId,
			@Name("durationInDays") @Description("Optional: how many days the voting phase runs. Default: the server's configured duration.") Integer durationInDays
	) throws LiquidoException {
		PollEntity poll = pollService.getPollInCurrentTeam(pollId);
		return pollService.startVotingPhase(poll, durationInDays);
	}

	/**
	 * Get a one-ime voter token for casting a ballot in this poll.
	 *
	 * @param pollId ID of the poll
	 * @return { "voterToken": "$2ADDgg33gva...." }
	 * @throws LiquidoException when user is not logged into a team
	 */
	@Query
	@Description("Get a one-time voter token to cast a vote in this poll. This token is only valid for this user and this poll. And it can only be used once!")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public String voterToken(
			@NonNull Long pollId
	) throws LiquidoException {
		UserEntity voter = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.unauthorized("You MUST be logged in to get a voterToken!"));
		PollEntity poll = pollService.getPollInCurrentTeam(pollId);
			return castVoteService.createOneTimeVoterToken(voter, poll);
	}

	/**
	 * Cast a vote in a poll
	 *
	 * <p>Voting in liquido is completely <b>anonymous</b>. This GraphQL mutatin can be called anonymously.
	 * The voter is only authenticated via his voterToken that he received previously.
	 * The poll.id and the team.id are backed into this token.</p>
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
			@Description("The poll you want to cast a vote in")
			@NonNull long pollId,
			@Description("The proposals as sorted by the voter")
			@NonNull List<Long> voteOrderIds,   // Must be passed as [BigInteger!]!  in GraphQL
			@Description("The plain voter token that the voter has received for this poll.")
			@NonNull String voterToken
	) throws LiquidoException {
		// SECURITY: deliberately NOT team-scoped like the other poll lookups in this class (see
		// PollService.getPollInCurrentTeam). This call is anonymous, so there is no current team to
		// check against. It doesn't need one either: the voterToken is already poll-bound twice
		// (poll.id is mixed into its hash in CastVoteService.calcHashedVoterToken, and
		// OneTimeVotingToken.poll is a FK), so a token cannot be replayed against another poll.
		// The team boundary for voting is enforced entirely at voterToken() above, the only
		// authenticated step. Adding a team check here would require deriving a team from the
		// RightToVote -- exactly the named-user<->ballot link that ballot secrecy forbids.
		// (see CastVoteService.calcHashedVoterToken and OneTimeVotingToken.poll)
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot cast vote. Poll(id="+pollId+") not found!"));
		CastVoteResponse res = castVoteService.castVote(voterToken, poll, voteOrderIds);
		log.info("castVote: poll.id={}", pollId);		//TODO: log all user actions into separate file or even better into some business process data mining analytics tool. (buzzword bingo)
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
		PollEntity poll = pollService.getPollInCurrentTeam(pollId);
		return pollService.finishVotingPhase(poll);
	}


	/**
	 * Get the ballot of a voter in a poll if the voter has already cast one.
	 * Security: A voter can of course only get his own ballot. Not the ballots of others.
	 * @param pollId poll.id
	 * @return the voter's ballot if there is one
	 * @throws LiquidoException when poll cannot be found
   */
	@Query("myBallot")
	@Description("Get the ballot of a voter in a poll, if the voter has already casted one.")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public Optional<BallotEntity> getBallotOfCurrentUser(
			@NonNull long pollId
	) throws LiquidoException {
		PollEntity poll = pollService.getPollInCurrentTeam(pollId);
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
		// SECURITY: deliberately NOT team-scoped, same reasoning as castVote() above -- this is an
		// anonymous, checksum-keyed lookup with no current team to check against.
		PollEntity poll = PollEntity.<PollEntity>findByIdOptional(pollId)
				.orElseThrow(LiquidoException.notFound("Cannot verify checksum. Poll(id="+pollId+") not found!"));
		return BallotEntity.findByPollAndChecksum(poll, checksum)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_VERIFY_CHECKSUM, "No known ballot for that checksum."));
	}

	
}