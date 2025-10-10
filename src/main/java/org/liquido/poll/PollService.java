package org.liquido.poll;

import io.quarkus.scheduler.Scheduler;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.NonNull;
import org.liquido.model.BaseEntity;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamEntity;
import org.liquido.user.DelegationEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;
import org.liquido.util.Lson;
import org.liquido.vote.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This service class contains all the business logic around Polls.
 * Create polls, add proposals to a poll, start the voting phase, get voter token, cast a vote,
 * end a voting phase and calculate the winner of a poll.
 */
@Slf4j
@ApplicationScoped
public class PollService {

	@Inject
	LiquidoConfig config;

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	Scheduler scheduler;

	@Inject
	CastVoteService castVoteService;

	/**
	 * Create a new poll inside a team. Only the admin is allowed to create a poll in a team
	 * @param title Title of the new poll
	 * @param team The admin's team.
	 * @return the new poll
	 */
	@RolesAllowed(JwtTokenUtils.LIQUIDO_ADMIN_ROLE)
	public PollEntity createPoll(@NonNull String title, TeamEntity team) throws LiquidoException {
		PollEntity poll = new PollEntity(title);
		team.getPolls().add(poll);
		poll.setTeam(team);

		//TODO: Automatically schedule voting phase if configured.
		/*
		if (config.daysUntilVotingStarts > 0) {
			LocalDateTime votingStart = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS).plusDays(prop.daysUntilVotingStarts);
			poll.setVotingStartAt(votingStart);
			poll.setVotingEndAt(votingStart.plusDays(prop.durationOfVotingPhase));
		}
		 */

		poll.persist();
		log.info("Created new " + poll);
		return poll;
	}

	/**
	 * Add a proposals (ie. an ideas that reached its quorum) to an already existing poll and save the poll.
	 *
	 * Preconditions
	 * <ol>
	 *   <li>Proposal must be in status PROPOSAL</li>
	 *   <li>Poll must be in ELABORATION phase.</li>
	 *   <li>Proposal must be in the same area as the poll.</li>
	 *   <li>Proposal must not already be part of another poll.</li>
	 *   <li>Poll must not yet contain this proposal.</li>
	 *   <li>Poll must not yet contain a proposal with the same title.</li>
	 *   <li>User must not yet have a proposal in this poll.</li>
	 * </ol>
	 *
	 * @param proposal a proposal (in status PROPOSAL)
	 * @param poll a poll in status ELABORATION
	 * @return the newly created poll
	 * @throws LiquidoException if area or status of proposal or poll is wrong. And also when user already has a proposal in this poll.
	 */
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	public PollEntity addProposalToPoll(@NonNull ProposalEntity proposal, @NonNull PollEntity poll) throws LiquidoException {
		// sanity checks
		if (proposal.getStatus() != ProposalEntity.LawStatus.PROPOSAL)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposalToPoll: poll(id="+poll.getId()+"): Proposal(id="+proposal.getId()+") is not in state PROPOSAL.");
		if (poll.getStatus() != PollEntity.PollStatus.ELABORATION)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposalToPoll: Poll(id="+poll.getId()+") is not in ELABORATION phase");
		//if (poll.getProposals().size() > 0 && !proposal.getArea().equals(poll.getArea()))
		//	throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposalToPoll: Proposal must be in the same area as the other proposals in poll(id="+poll.getId()+")");
		if (proposal.getPoll() != null)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposalToPoll: proposal(id="+proposal.getId()+") is already part of another poll.");
		if(poll.getProposals().contains(proposal))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Poll.id="+poll.getId()+" already contains proposal ="+proposal.toStringShort());
		if(poll.getProposals().stream().anyMatch(p -> p.title.equals(proposal.title)))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposalToPoll: Poll(id="+poll.getId()+") already contains a proposal with that title: '" + proposal.title + "'");

		// Current user must not already have a proposal in this poll. Expect the admin may add more proposals.
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Cannot add proposal. Must be logged in!"));

		if (!jwtTokenUtils.isAdmin() && poll.getProposals().stream().anyMatch(prop -> currentUser.equals(prop.createdBy)))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ADD_PROPOSAL, "Cannot addProposal: " + currentUser.toStringShort() + " already has a proposal in poll(id="+poll.getId()+")");
		// Admin could also be fetched this way: poll.getTeam().isAdmin(currentUser);   But who knows how old the passed poll is.
		// Keep in mind that proposal.getCreatedBy() might not be filled yet!

		proposal.setStatus(ProposalEntity.LawStatus.ELABORATION);
		poll.getProposals().add(proposal);
		proposal.setPoll(poll);
		poll.persist();
		log.debug("Added "+proposal+" to poll(id="+poll.getId()+")");
		return poll;
	}

	/**
	 * Start the voting phase of the given poll.
	 * Poll must be in elaboration phase and must have at least two proposals
	 * @param poll a poll in elaboration phase with at least two proposals
	 * @return the poll that is now in status VOTING
	 */
	@Transactional
	public PollEntity startVotingPhase(@NonNull PollEntity poll) throws LiquidoException {
		log.info("startVotingPhase of " + poll);
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
		poll.setVotingEndAt(votingStart.truncatedTo(ChronoUnit.DAYS).plusDays(config.durationOfVotingPhase()));     //voting ends in n days at midnight
		poll.persist();

		//----- schedule a Job that will finish the voting phase at poll.votingEndAt() date
		/* DOES NOT WORK
		scheduler.newJob("finishVotingPhase")
				.setCron("...")
				.setTask(executionContext -> {
					//endVotingPhase
				})
				.schedule();
		 */

		/*
		try {
			Date votingEndAtDate = Date.from(poll.getVotingEndAt().atZone(ZoneId.systemDefault()).toInstant());
			scheduleJobToFinishPoll(poll, votingEndAtDate);
		} catch (SchedulerException e) {
			String msg = "Cannot start voting phase, because of scheduler error";
			log.error(msg, e);
			throw new LiquidoException(LiquidoException.Errors.CANNOT_START_VOTING_PHASE, msg, e);
		}

		 */

		return poll;
	}

	/** An auto-configured spring bean that gives us access to the Quartz scheduler
	@Inject
	SchedulerFactoryBean schedulerFactoryBean;

	/**
	 * Schedule a Quartz job that will end the voting phase of this poll
	 * @param poll a poll in voting phase
	 * @throws SchedulerException when job cannot be scheduled

	private void scheduleJobToFinishPoll(@NonNull PollEntity poll, Date votingEndAtDate) throws SchedulerException {
		JobKey finishPollJobKey = new JobKey("finishVoting_pollId="+poll.getId(), "finishPollJobGroup");

		JobDetail jobDetail = newJob(FinishPollJob.class)
				.withIdentity(finishPollJobKey)
				.withDescription("Finish voting phase of poll.id="+poll.getId())
				.usingJobData("poll.id", poll.getId())
				.storeDurably()
				.build();

		Trigger trigger = newTrigger()
				.withIdentity("finishVotingTrigger_poll.id="+poll.getId(), "finishPollTriggerGroup")
				.withDescription("Finish voting phase of poll.id="+poll.getId())
				.startAt(votingEndAtDate )
				.withSchedule(SimpleScheduleBuilder.simpleSchedule()
						.withRepeatCount(0)
						.withIntervalInMinutes(1)
						.withMisfireHandlingInstructionFireNow()		// If backend was down at the time when the Job would have been scheduled, then fire the job immidiately when the app is back up
				)
				.build();

		try {
			//Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
			Scheduler scheduler =  schedulerFactoryBean.getScheduler();
			if (!scheduler.isStarted())
				log.warn("Quartz job scheduler is not started. It should be started!");
			scheduler.scheduleJob(jobDetail, trigger);
		} catch (SchedulerException e) {
			log.error("Cannot schedule task to finish poll.", e);
			throw e;
		}
	}
	*/

	/**
	 * Finish the voting phase of a poll and calculate the winning proposal.
	 * @param poll A poll in voting phase
	 * @return Winning proposal of this poll that now is a law.
	 * @throws LiquidoException When poll is not in voting phase
	 */
	@Transactional
	public ProposalEntity finishVotingPhase(@NonNull PollEntity poll) throws LiquidoException {
		log.info("finishVotingPhase of "+poll);
		if (!PollEntity.PollStatus.VOTING.equals(poll.getStatus()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_FINISH_POLL, "Cannot finishVotingPhase: Poll must be in status VOTING.");

		poll.setStatus(PollEntity.PollStatus.FINISHED);
		poll.setVotingEndAt(LocalDateTime.now());
		poll.getProposals().forEach(p -> p.setStatus(ProposalEntity.LawStatus.LOST));

		//----- calc winner of poll
		List<BallotEntity> ballots = BallotEntity.list("poll", poll);
		ProposalEntity winningProposal = calcWinnerOfPoll(poll, ballots);
		log.info("Winner of Poll(id="+poll.getId()+") is "+winningProposal);

		//----- save results
		if (winningProposal != null) {
			winningProposal.setStatus(ProposalEntity.LawStatus.LAW);
			poll.setWinner(winningProposal);
			winningProposal.persist();
		}
		poll.persist();
		return winningProposal;
	}

	/**
	 * Calculate the pairwise comparison of every pair of proposals in every ballot's voteOrder.
	 *
	 * This method just extracts all the IDs from poll and ballots and the forwards to the
	 * {@link RankedPairVoting#calcRankedPairWinners(Matrix)} method.
	 *
	 * @param poll a poll that just finished its voting phase
	 * @param ballots the ballots casted in this poll
	 * @return the duelMatrix, which counts the number of preferences for each pair of proposals.
	 * @throws LiquidoException When poll is not in status FINISHED
	 */
	@Transactional
	public ProposalEntity calcWinnerOfPoll(@NonNull PollEntity poll, @NonNull List<BallotEntity> ballots) throws LiquidoException {
		if (!PollEntity.PollStatus.FINISHED.equals(poll.getStatus()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_FINISH_POLL, "Poll must be in status finished to calcDuelMatrix!");

		// Ordered list of proposal IDs in poll.  (Keep in mind that the proposal in a poll are not ordered.)
		List<Long> allIds = poll.getProposals().stream().map(BaseEntity::getId).collect(Collectors.toList());

		// map the vote order of each ballot to a List of ids
		List<List<Long>> idsInBallots = ballots.stream().map(
				ballot -> ballot.getVoteOrder().stream().map(BaseEntity::getId).collect(Collectors.toList())
		).collect(Collectors.toList());

		// wizardry mathematical magic :-)
		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, idsInBallots);
		poll.setDuelMatrix(duelMatrix);

		List<Integer> winnerIndexes = RankedPairVoting.calcRankedPairWinners(duelMatrix);
		if (winnerIndexes.size() == 0) {
			log.warn("There is no winner in poll "+poll);  // This may for example happen when there are no votes at all.
			return null;
		}
		if (winnerIndexes.size() > 1) log.warn("There is more than one winner in "+poll);
		long firstWinnerId = allIds.get(winnerIndexes.get(0));
		for(ProposalEntity prop: poll.getProposals()) {
			if (prop.getId() == firstWinnerId)	return prop;
		}
		throw new RuntimeException("Couldn't find winning Id in poll.");  // This should mathematically never happen!
	}


	public Lson calcPollResults(PollEntity poll) {
		Long ballotCount = BallotEntity.count("poll", poll);
		return Lson.builder()
				.put("winner", poll.getWinner())
				.put("numBallots", ballotCount)
				.put("duelMatrix", poll.getDuelMatrix());
	}

	/**
	 * Get the number of ballots in a currently running poll in VOTING.
	 * @param poll a poll in VOTING
	 * @return the number of cast ballots.
	 */
	public long getNumCastedBallots(PollEntity poll) {
		return BallotEntity.count("poll", poll);
	}

	/**
	 * Find the ballot that a user cast in a poll. Since every ballot is anonymous,
	 * we look up the user's RightToVote and then can find the linked ballot.
	 *
	 * @param poll a poll at least in the voting phase
	 * @return the ballot of the currently logged in user in that poll (if any)
	 * @throws LiquidoException when something is wrong
	 */
	public Optional<BallotEntity> getBallotOfCurrentUser(PollEntity poll) throws LiquidoException {
		if (PollEntity.PollStatus.ELABORATION.equals(poll.getStatus()))
				throw new LiquidoException(LiquidoException.Errors.INVALID_POLL_STATUS, "Cannot get ballot of poll in ELABORATION");
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Must be logged in to lookup your ballot in a poll!"));
		RightToVoteEntity rightToVote = RightToVoteEntity.findByVoter(currentUser, config.hashSecret())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "You have no valid RightToVote!"));

		return BallotEntity.findByPollAndRightToVote(poll, rightToVote);
	}

	/**
	 * Checks if the ballot with that checksum was counted correctly in the poll.
	 *
	 * The difference between this and {@link #getBallotOfCurrentUser(PollEntity)} is that
	 * this verification also checks the voteOrder which is encoded into the ballot's checksum.
	 *
	 * @param poll the poll where the ballot was casted in
	 * @param checksum a ballot's checksum as returned by /castVote
	 * @return The ballot when checksum is correct or Optional.emtpy() if no ballot with that checksum could be found.
	 */
	public Optional<BallotEntity> getBallotForChecksum(@NotNull PollEntity poll, String checksum) {
		return BallotEntity.findByPollAndChecksum(poll, checksum);
	}

	/**
	 * When a user wants to check how his direct proxy has voted for him.
	 * @param poll a poll
	 * @param rightToVote the voter's checksum
	 * @return (optionally) the ballot of the vote's direct proxy in this poll, if voter has a direct proxy
	 * @throws LiquidoException when this voter did not delegate his checksum to any proxy in this area
	 */
	public Optional<BallotEntity> getBallotOfDirectProxy(PollEntity poll, RightToVoteEntity rightToVote) throws LiquidoException {
		if (PollEntity.PollStatus.ELABORATION.equals(poll.getStatus()))
			throw new LiquidoException(LiquidoException.Errors.INVALID_POLL_STATUS, "Cannot get ballot of poll in ELABORATION");
		return BallotEntity.findByPollAndRightToVote(poll, rightToVote.getDelegatedTo());
	}

	public Optional<BallotEntity> getBallotOfTopProxy(PollEntity poll, RightToVoteEntity rightToVote) throws LiquidoException {
		if (PollEntity.PollStatus.ELABORATION.equals(poll.getStatus()))
			throw new LiquidoException(LiquidoException.Errors.INVALID_POLL_STATUS, "Cannot get ballot of poll in ELABORATION");
		if (rightToVote.getDelegatedTo() == null) return Optional.empty();
		RightToVoteEntity topChecksum = findTopChecksumRec(rightToVote);
		return BallotEntity.findByPollAndRightToVote(poll, topChecksum);
	}

	private RightToVoteEntity findTopChecksumRec(RightToVoteEntity rightToVote) {
		if (rightToVote.getDelegatedTo() == null) return rightToVote;
		return findTopChecksumRec(rightToVote.getDelegatedTo());
	}

	/**
	 * Find the proxy that cast the vote in this poll.
	 * This recursive method walks up the tree of DelegationModels and RightToVote delegations in parallel
	 * until it reaches a ballot with level == 0. That is the ballot cast by the effective proxy.
	 *
	 * This may be the voter himself, if he voted himself.
	 * This may be the voters direct proxy
	 * Or this may be any other proxy up in the tree, not necessarily the top proxy.
	 * Or there might be no effective proxy yet, when not the voter nor his proxies voted yet in this poll.
	 * *
	 * @param poll a poll in voting or finished
	 * @param voter The voter to check who may have delegated his right to vote to a proxy.
	 * @return Optional.empty() IF there is no ballot for this checksum, ie. user has not voted yet at all.
	 *         Optional.of(voter) IF voter has not delegated his checksum to any proxy. (Or maybe only requested a delegation)
	 *				 Optional.of(effectiveProxy) where effective proxy is the one who actually voted (ballot.level == 0) for the voter
	 * @throws LiquidoException When poll is in status ELABORATION or
	 */
	public Optional<UserEntity> findEffectiveProxy(PollEntity poll, UserEntity voter) throws LiquidoException {
		if (PollEntity.PollStatus.ELABORATION.equals(poll.getStatus()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_FIND_ENTITY, "Cannot find effective proxy, because poll is not in voting phase or finished");
		RightToVoteEntity rightToVote = RightToVoteEntity.findByVoter(voter, config.hashSecret())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_FIND_ENTITY, "Cannot find effective Proxy, you have no RightTotVote"));
		return findEffectiveProxyRec(poll, voter, rightToVote);
	}

	/**
	 * Private recursive part of finding the effective proxy. This walks up the tree of delegations until you we find
	 * the proxy that actually cast the vote.
	 * @return the proxy that cast the vote or the voter if he cast a vote himself.
	 */
	private Optional<UserEntity> findEffectiveProxyRec(PollEntity poll, UserEntity voter, RightToVoteEntity rightToVote) {
		if (rightToVote.getPublicProxy() != null &&	!rightToVote.getPublicProxy().equals(voter))
			throw new RuntimeException("Data inconsistency: " + rightToVote + " is not the checksum of public proxy="+voter);

		//----- Check if there is a ballot for this RightToVote. If not, this voter did not vote yet.
		Optional<BallotEntity> ballot = BallotEntity.findByPollAndRightToVote(poll, rightToVote);
		if (ballot.isEmpty()) return Optional.empty();

		//----- If a ballot has level 0, then this voter/proxy voted for himself.
		if (ballot.get().getLevel() == 0) return Optional.of(voter);

		//----- If a voter has a ballot with level > 0, ie. casted by his proxy, but currently has not delegated his RightToVote to any proxy, then this his vote.
		// This exceptional case may happen when the voter removed his delegation, after his proxy voted for him.     //TODO: create a Test for this
		if (rightToVote.getDelegatedTo() == null) return Optional.of(voter);

		//TODO: very very edge case: What shall happen when a voter's proxy already voted and the voter then changes his delegation to another proxy.

		//----- Get voters direct proxy, which must exist because the voters checksum is delegated
		DelegationEntity delegation = DelegationEntity.<DelegationEntity>find("fromUser", voter).firstResultOptional()
				.orElseThrow(() -> new RuntimeException("Data inconsistency: Voter has a delegated checksum but no direct proxy! "+voter+", "+rightToVote));

		//----- at last recursively check for that proxy up in the tree.
		return findEffectiveProxyRec(poll, delegation.getToProxy(), rightToVote.getDelegatedTo());

		//of course the order of all the IF statements in this method is extremely important. Managed to do it without any "else" !! :-)
	}

	/**
	 * Delete a poll and all ballots casted in it.
	 * @param poll The poll to delete
	 * @param deleteProposals wether to delete the porposals in the poll
	 */
	@RolesAllowed(JwtTokenUtils.LIQUIDO_ADMIN_ROLE)  // only the admin may delete polls.  See application.properties for admin name and email
	@Transactional
	public void deletePoll(@NonNull PollEntity poll, boolean deleteProposals) {
		log.info("DELETE "+poll);
		if (poll == null) return;

		//TODO: !!! check that poll is in current user's team !!!

		// unlink proposals/laws from poll and then (optionally) delete them
		for (ProposalEntity prop : poll.getProposals()) {
			prop.setPoll(null);
			if (deleteProposals) {
				prop.delete();
			} else {
				prop.persist();
			}
		}

		// Delete casted Ballots in poll
		for (BallotEntity ballot : BallotEntity.<BallotEntity>list("poll", poll)) {
			ballot.delete();
		}

		// Delete the poll
		poll.delete();
	}
}