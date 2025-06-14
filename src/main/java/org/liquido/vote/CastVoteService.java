package org.liquido.vote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * This service handles everything related to casting a vote.
 */
@Slf4j
@ApplicationScoped
public class CastVoteService {

	@Inject
	LiquidoConfig config;

	// Some more resources around secure authentication with tokens:
	//TODO: create really secure voterTokens like this: U2F  https://blog.trezor.io/why-you-should-never-use-google-authenticator-again-e166d09d4324
	//TODO: RSA Tokens  https://stackoverflow.com/questions/37722090/java-jwt-with-public-private-keys
	// OpenID Nice article  https://connect2id.com/learn/openid-connect#id-token

	//TODO: I could make this more secure by creating different voter tokens for each poll.

	/**
	 * When a user wants to cast a vote in LIQUIDO, then they need a voterToken
	 *
	 * The backend calculates two values:
	 *
	 * (1) The voterToken which will be returned to the voter. It is secret and must only be known to him.
	 *     With this voterToken the voter is able to anonymously cast a vote.
	 *     The voterToken is calculated from several seeds, including a serverSecret, so that we can be sure
	 *     only "we" create valid voterTokens. Each user has one voter Token per area.
	 *
	 * <pre>voterToken = hash(user.id + voterTokenSecret + area.id + serverSecret)</pre>
	 *
	 * (2) The hashed voterToken is the digital representation of the user's right to vote. Only that user knows his voterToken,
	 *     so only he can prove that this is his rightToVote. The rightToVote is only stored on the server and not returned.
	 * <pre>rightToVote = hash(voterToken + serverSecret)</pre>
	 *
	 * When creating new voterTokens, then we do not calculate a new BCrypt salt. Because a voter might request his voterToken
	 * multiple times. And we have to return the same voterToken every time.
	 *
	 * When a user requests a new voterToken, he may immediately decide to become a public proxy. Then his rightToVote
	 * is associated with his username, so that other voters can immediately delegate their right to vote to his.
	 * A voter may also later decide to become a public proxy.
	 *
	 * @param voter the currently logged in and correctly authenticated user
	 * @param voterTokenSecret  a secret that only the user knows. So no one else can create his voterToken.
	 * @param becomePublicProxy true if voter wants to become (or stay) a public proxy. A voter can also decide this later with becomePublicProxy
	 * @return the user's voterToken, that only the user must know, and that will hash to the stored rightToVote. The user can cast votes with this voterToken in that area.
	 */
	@Transactional
	public String createVoterTokenAndStoreRightToVote(UserEntity voter, String voterTokenSecret, boolean becomePublicProxy) throws LiquidoException {
		log.debug("createVoterTokenAndStoreRightToVote: for "+voter.toStringShort()+", becomePublicProxy="+becomePublicProxy);
		if (voter == null || DoogiesUtil.isEmpty(voter.getEmail()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_GET_TOKEN, "Need voter to build a voterToken!");
		if (DoogiesUtil.isEmpty(voterTokenSecret))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_GET_TOKEN, "Need your tokenSecret to build a voterToken!");

		// Create a new voterToken for this user in that area with the BCRYPT hashing algorithm
		// Remark: Bcrypt prepends the salt into the returned token value!!!
		String voterToken =       calcVoterToken(voter.id, voterTokenSecret);   // voterToken that only this user must know
		String hashedVoterToken = calcHashedVoterToken(voterToken);             // hash of voterToken that can only be generated from the users voterToken and only by the server.

		//   IF there is an already existing rightToVote for that voter as public proxy
		//  AND there is NO existing rightToVote for that voterToken OR one that does not match the public proxies rightToVote,
		// THEN the user has changed his voterTokenSecret
		//  AND we must invalidate (delete) the old rightToVote.
		//  AND we must delete all delegations.
		Optional<RightToVoteEntity> rightToVoteOpt = RightToVoteEntity.findByHashedVoterToken(hashedVoterToken);
		Optional<RightToVoteEntity> rightToVoteOfPublicProxyOpt  = RightToVoteEntity.findByPublicProxy(voter);
		if (rightToVoteOfPublicProxyOpt.isPresent() &&
				!rightToVoteOfPublicProxyOpt.equals(rightToVoteOpt)) {
			log.trace("Voter changed his voterTokenSecret. Replacing old rightToVote of former public proxy with new one.");

			throw new RuntimeException("Change of voterTokenSecret is NOT YET IMPLEMENTED!");
			/*
			List<DelegationModel> delegations = delegationRepo.findByAreaAndToProxy(area, voter);
			List<ChecksumModel> delegees = checksumRepo.findByDelegatedTo(existingChecksumOfPublicProxy);
			if (becomePublicProxy) {
				for(ChecksumModel delegatedChecksum : delegees) {
					delegatedChecksum.setDelegatedTo(checksumModel);
					checksumRepo.save(delegatedChecksum);
				}
			} else {
				for(ChecksumModel delegatedChecksum : delegees) {
					delegatedChecksum.setDelegatedTo(null);
					checksumRepo.save(delegatedChecksum);
				}
				delegationRepo.deleteAll(delegations);
			}
			checksumRepo.delete(existingChecksumOfPublicProxy);
			*/
		}

		// ----- upsert rightToVote (BUGFIX: must be done BEFORE becomePublicProxy)
		if (rightToVoteOpt.isPresent()) { log.trace("  Update existing rightToVote");	}
		RightToVoteEntity rightToVote = rightToVoteOpt.orElse(new RightToVoteEntity(hashedVoterToken));
		refreshRightToVote(rightToVote);
		rightToVote.persist();

		//   IF a user wants to become a public proxy,
		// THEN stores his username with his rightToVote
		//  AND automatically accept all pending delegationRequests
		if (becomePublicProxy) {
			//TODO: proxyService.becomePublicProxy(voter, voterToken);
		}

		return voterToken;		// IMPORTANT! return the voterToken and not the rightToVote. The rightToVote is only stored on the server.
	}

	/**
	 * Refresh the expiration time of this valid rightToVote. You must save the update yourself!
	 * @param rightToVote the voter's encoded right to vote
	 */
	@Transactional
	public void refreshRightToVote(RightToVoteEntity rightToVote) {
		rightToVote.setExpiresAt(LocalDateTime.now().plusHours(config.rightToVoteExpirationHours()));
		rightToVote.persist();
	}

	/**
	 * Very thoroughly check if the passed voterToken is valid, ie. its rightToVote=hash(voterToken) is already known.
	 * This method will not create a new rightToVote. createVoterTokenAndStoreRightToVote() must have been called before.
	 * @param voterToken the token to check
	 * @return the voter's rightToVote if voterToken is valid
	 * @throws LiquidoException when voterToken is invalid or its corresponding rightToVote is not known.
	 */
	public RightToVoteEntity isVoterTokenValid(String voterToken) throws LiquidoException {
		if (voterToken == null || voterToken.length() < 10)
			throw new LiquidoException(LiquidoException.Errors.INVALID_VOTER_TOKEN, "Voter token is empty or has wrong format");  // BCRYPT hashes start with $2$ or $2
		String hashedVoterToken = calcHashedVoterToken(voterToken);
		RightToVoteEntity rightToVote = RightToVoteEntity.findByHashedVoterToken(hashedVoterToken)
				.orElseThrow(() -> (new LiquidoException(LiquidoException.Errors.INVALID_VOTER_TOKEN, "Voter token is invalid. It has not right to vote.")));
		return rightToVote;
	}

	/*
	 * Get the already existing checksum of this user in that area.
	 * This method will not create a new voterToken or store any checksum.
	 * When there is no checksum, that is an error. Normally a valid voterToken and its stored checksum should always exist together.
	 * So therefore we throw an exception when voterToken is invalid and/or its checksum is not found (instead of returning an Optional)
	 *
	 * This method is also used for tests.
	 *
	 * @param user a voter
	 * @param userTokenSecret the voter's private token secret, only known to him. This is used to create the voterToken
	 * @param area area of voterToken and checksum
	 * @return the existing checksum of that user in that area
	 * @throws LiquidoException when user does not yet have checksum in that area

	public RightToVoteEntity getExistingRightToVote(UserEntity user, String userTokenSecret, AreaModel area) throws LiquidoException {
		String voterToken = calcVoterToken(user.getId(), userTokenSecret, area.getId());
		return isVoterTokenValidAndGetChecksum(voterToken);
	}
	*/


	/**
	 * User casts own vote. Keep in mind, that this method is called anonymously. No UserEntity involved.
	 * If that user is a proxy for other voters, then their ballots will also be added automatically.
	 * @param voterToken anonymous voterToken. Voter must have fetched a valid token via getVoterToken before casting this vote.
	 * @param poll the poll to cast the vote in.
	 * @param voteOrderIds list of IDs as sorted by the user. IDs must of course come from poll.proposals. No ID may appear more than once!
	 * @return CastVoteResponse with ballot and the voteCount how often the vote was actually counted for this proxy. (Some voters might already have voted on their own.)
	 * @throws LiquidoException when voterToken is invalid or there is <b>anything</b> suspicious with the ballot
	 */
	@Transactional
	public CastVoteResponse castVote(String voterToken, PollEntity poll, List<Long> voteOrderIds) throws LiquidoException {
		log.info("castVote(poll="+poll+", voteOrderIds="+voteOrderIds+")");

		// CastVoteRequest must contain a poll
		if (poll == null || poll.getId() == null)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Need poll to cast vote");

		// Poll must be in status voting
		if (!PollEntity.PollStatus.VOTING.equals(poll.getStatus()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Poll must be in status VOTING");

		// Sanity check voterToken (computational expensive thorough check will be done below)
		if (DoogiesUtil.isEmpty(voterToken))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Need voterToken to cast vote");

		// voterOrder must contain at least one element
		if (voteOrderIds == null || voteOrderIds.size() == 0)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Need voteOrder to cast vote");

		// Convert voteOrderIds to list of actual ProposalEntities from poll.
		// Therefore voteOrderIds must only contain proposal.ids from this poll and it must not not contain any ID more than once!
		List<ProposalEntity> voteOrder = new ArrayList<>();
		Map<Long, ProposalEntity> pollProposals = new HashMap<>();
		for (ProposalEntity prop : poll.getProposals()) {
			pollProposals.put(prop.getId(), prop);
		}

		for (Long propId : voteOrderIds) {
			ProposalEntity prop = pollProposals.get(propId);
			if (prop == null)
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "All proposal you want to vote for must be from poll(id="+poll.id+"). Proposal(id="+propId+") isn't");
			if (voteOrder.contains(prop))
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Your voteOrder must not contain any proposal twice! Proposal(id="+ propId+") appears twice.");
			voteOrder.add(prop);
		}

		// Validate voterToken against stored RightToVotes
		RightToVoteEntity RightToVoteEntity = isVoterTokenValid(voterToken);

		// Create new ballot for the voter himself at level 0
		BallotEntity newBallot = new BallotEntity(poll, 0, voteOrder, RightToVoteEntity);

		// check this ballot and recursive cast ballots for delegated rightToVotes
		CastVoteResponse castVoteResponse = castVoteRec(newBallot);

		return castVoteResponse;
	}

	/**
	 * This method calls itself recursively. The <b>upsert</b> algorithm for storing a ballot works like this:
	 *
	 * 1) Check the integrity of the passed newBallot. Especially check the validity of its RightToVoteEntity.
	 *    The rightToVote must be known.
	 *
	 * 2) IF there is NO existing ballot for this poll yet,
	 *    THEN save a new ballot
	 *    ELSE // a ballot already exists
	 *      IF the level of the existing ballot is SMALLER then the passed newBallot.level
	 *      THEN do NOT update the existing ballot, because it was casted by a lower proxy or the voter himself
	 *      ELSE update the existing ballot's level and vote order
	 *
	 *  3) FOR EACH directly delegated RightToVote
	 *              build a childBallot and recursively cast this childBallot.
	 *
	 *  Remark: The child ballot might not be stored when there already is one with a smaller level. This is
	 *          our recursion limit.
	 *
	 * @param newBallot the ballot that shall be stored. The ballot will be checked very thoroughly. Especially if the ballot's right to vote is valid.
	 * @return the newly created or updated existing ballot  OR
	 *         null if the ballot wasn't stored due to an already existing ballot with a smaller level.
	 */
	//@Transactional   //Do not open a transaction for each recursion!
	private CastVoteResponse castVoteRec(BallotEntity newBallot) throws LiquidoException {
		log.debug("   castVoteRec: "+newBallot);

		//----- check validity of the ballot
		checkBallot(newBallot);

		//----- check if there already is a ballot, then update that, otherwise save newBallot
		Optional<BallotEntity> existingBallotOpt = BallotEntity.findByPollAndRightToVote(newBallot.getPoll(), newBallot.getRightToVote());
		BallotEntity savedBallot;

		if (existingBallotOpt.isPresent()) {
			//----- Update existing ballot, if level of newBallot is smaller.  Proxy must not overwrite a voter's own vote  OR  a vote from a proxy below him
			BallotEntity existingBallot = existingBallotOpt.get();
			if (existingBallot.getLevel() < newBallot.getLevel()) {
				log.trace("   Will not overwrite existing ballot with vote at already smaller level " + existingBallot);
				return null;
			}
			log.trace("  Update existing ballot "+existingBallot.id);
			existingBallot.setVoteOrder(newBallot.getVoteOrder());
			existingBallot.setLevel(newBallot.getLevel());
			existingBallot.persist();
			savedBallot = existingBallot;
		} else {
			//----- If there is no existing ballot yet with that rightToVote, then builder a completely new one.
			log.trace("   Saving new ballot");
			newBallot.persist();
			savedBallot = newBallot;
		}

		//----- When user is a proxy, then recursively cast a ballot for each delegated rightToVote
		long voteCount = 0;   // count for how many delegees (that have not voted yet for themselves) the proxies ballot is also casted
		List<RightToVoteEntity> delegatedRights = RightToVoteEntity.findByDelegatedTo(savedBallot.getRightToVote());
		for (RightToVoteEntity delegatedRightToVote : delegatedRights) {
			List<ProposalEntity> voteOrderClone = new ArrayList<>(newBallot.getVoteOrder());   // BUGFIX for org.hibernate.HibernateException: Found shared references to a collection
			BallotEntity childBallot = new BallotEntity(newBallot.getPoll(), newBallot.getLevel() + 1, voteOrderClone, delegatedRightToVote);
			log.debug("   Proxy casts vote for delegated childBallot "+childBallot);
			CastVoteResponse childRes = castVoteRec(childBallot);  // will return null when level of an existing childBallot is smaller then the childBallot that the proxy would cast. => this ends the recursion
			if (childRes != null) voteCount += 1 + childRes.getVoteCount();
		}

		// voteCount does NOT include the voters (or proxies) own ballot
		return new CastVoteResponse(savedBallot, voteCount);
	}

	/**
	 * Check if a ballot is valid for casting a vote.
	 * @param ballot a cast vote with a sorted voteOrder inside.
	 * @throws LiquidoException when something inside ballot is invalid
	 */
	public void checkBallot(BallotEntity ballot) throws LiquidoException {
		// check that poll is actually in voting phase and has at least two alternative proposals
		PollEntity poll = ballot.getPoll();
		if (poll == null || !PollEntity.PollStatus.VOTING.equals(poll.getStatus())) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Poll must be in voting phase.");
		}
		if (poll.getProposals().size() < 2)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Poll must have at least two alternative proposals.");

		// check that voter Order is not empty
		if (ballot.getVoteOrder() == null || ballot.getVoteOrder().size() == 0) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE,"Cannot cast vote: VoteOrder is empty!");
		}

		// check that there is no duplicate vote for any one proposal
		HashSet<Long> proposalIds = new HashSet<>();
		for(ProposalEntity proposal : ballot.getVoteOrder()) {
			if (proposalIds.contains(proposal.getId())) {
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Duplicate vote for proposal_id="+proposal.getId());
			} else {
				proposalIds.add(proposal.getId());
			}
		}

		// check that all proposals you wanna vote for are in this poll and that they are also in voting phase
		for(ProposalEntity proposal : ballot.getVoteOrder()) {
			if (!proposal.getPoll().getId().equals(ballot.getPoll().getId()))   //BUGFIX: Cannot compare whole poll. Must compare IDs:  https://hibernate.atlassian.net/browse/HHH-3799  PersistentSet does not honor hashcode/equals contract when loaded eagerly
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Proposal(id="+proposal.getId()+") is not part of poll(id="+ballot.getPoll().getId()+")!");
			if (!ProposalEntity.LawStatus.VOTING.equals(proposal.getStatus())) {
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: proposals must be in voting phase.");
			}
		}

		// check that voter has a right to vote
		if (ballot.getRightToVote() == null || ballot.getRightToVote().getHashedVoterToken().length() < 5) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Invalid rightToVote. HashedVoterToken must be at least 5 chars!");
		}

		// Passed rightToVote in ballot MUST already exists in DB
		RightToVoteEntity rightToVote = RightToVoteEntity.findByHashedVoterToken(ballot.getRightToVote().getHashedVoterToken())
				.orElseThrow(() -> new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Passed rightToVote is not known."));

	}

	/**
	 * Verify a voter's ballot with its checksum. When the checksum is valid, the
	 * ballot with the correct voteOrder will be returned.
	 *
	 * @param pollId a poll
	 * @param checksum checksum of a ballot in that poll
	 * @return the voter's ballot if it matches the checksum.
	 * @throws LiquidoException when poll cannot be found
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
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_VERIFY_CHECKSUM, "No ballot for that checksum."));
	}

	//====================== private methods ========================

	/**
	 * Calculate the voter token for a user in this area.
	 * Each user has one voterToken for every area.
	 *
	 * @param userId Users's identification
	 * @param userTokenSecret secret that is only known to this user
	 * @return HEX representation of the SHA3_256 voterToken.
	 */
	private String calcVoterToken(Long userId, String userTokenSecret) {
		return DigestUtils.sha3_256Hex(userId + userTokenSecret + config.hashSecret());
	}

	/**
	 * Calculate the hashed voterToken. This is just the mathematical calculation. No validation is done here.
	 * Each user has one voterToken per area. So the rightToVote is also specific to this area.
	 * A rightToVote is hashed from the voterToken together with a secret.
	 *
	 * @param voterToken token passed from user
	 * @return hashedVoterToken = hash(voterToken + serverSecret)
	 */
	private String calcHashedVoterToken(String voterToken) {
		return DigestUtils.sha3_256Hex(voterToken + config.hashSecret());
	}

}