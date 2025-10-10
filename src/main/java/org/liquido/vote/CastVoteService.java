package org.liquido.vote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
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
	//OpenID Nice article  https://connect2id.com/learn/openid-connect#id-token

	/**
	 * When a user wants to cast a vote in LIQUIDO, then they need
	 *   1. A general RightToVote to be allowed to vote at all and
	 *   2. A one-time voterToken for that specific poll
	 *
	 * This method will generate a plainVoterToken, hash it and create a one-time VoterTokenEntity from this hash.
	 *
	 * @param voter the currently logged in and correctly authenticated user
	 * @param poll the voterToken is only valid for one vote in this poll
	 * @return the voter's plainVoterToken, that only this user must know
	 */
	@Transactional
	public String createOneTimeVoterToken(UserEntity voter, PollEntity poll) throws LiquidoException {
		log.debug("getVoterToken: for {} in poll.id={}", voter.toStringShort(), poll.id);
		if (DoogiesUtil.isEmpty(voter.getEmail()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_GET_TOKEN, "Need voter to build a OneTimeToken!");

		RightToVoteEntity rightToVote = RightToVoteEntity.findByVoter(voter, config.hashSecret())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_GET_TOKEN, "You are not allowed to vote!"));

		// Create a new one-time voter token for this poll and hash it with an additional salt.
		// The hash input must exactly be the same as in castVote(). It CANNOT contain voter.id, because that is not known in castVote()
		// I thought about adding an additional voterSecret, that a user passes in here and in castVote.
		// But plainVoterToken is already random. This would only add little security.
		String plainVoterToken  =  UUID.randomUUID().toString();
		String hashedVoterToken =  calcHashedVoterToken(plainVoterToken, poll.id);
		int ttl = config.voterTokenExpirationMinutes();
		VoterTokenEntity ott = VoterTokenEntity.buildAndPersist(hashedVoterToken, poll, rightToVote, ttl);

		// Only return the plainOneTimeToken to the voter. They can then use this token to anonymously cast one vote in this poll.
		return plainVoterToken;
	}

	/**
	 * Consume the one-time voterToken.
	 * Check that it is valid, known and hashes to a not yet expired VoterTokenEntity.
	 * And that a valid RightToVote is linked.
	 *
	 * @param plainVoterToken the plain voter token that the voter sent
	 * @param poll the poll we want to vote in.
	 * @return the voter's rightToVote if voterToken is valid
	 * @throws LiquidoException when voterToken is invalid or its corresponding rightToVote is not known.
	 */
	public RightToVoteEntity consumeVoterToken(String plainVoterToken, PollEntity poll) throws LiquidoException {
		if (plainVoterToken == null || plainVoterToken.length() < 10)
			throw new LiquidoException(LiquidoException.Errors.INVALID_VOTER_TOKEN, "This voterToken is valid.");

		// check voterToken
		String hashedVoterToken = calcHashedVoterToken(plainVoterToken, poll.id);
		log.info("consumeVoterToken: plainVoterToken = {} hashedVoterToken = {} in poll.id = {}", "XXXXXX", hashedVoterToken, poll.id);
		VoterTokenEntity voterToken = VoterTokenEntity.<VoterTokenEntity>findByIdOptional(hashedVoterToken)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.INVALID_VOTER_TOKEN, "This voterToken is invalid."));
		if (LocalDateTime.now().isAfter(voterToken.expiresAt))
			throw new LiquidoException(LiquidoException.Errors.INVALID_VOTER_TOKEN, "This voterToken is expired.");

		// check that it's linked to a right to vote
		if (voterToken.getRightToVote() == null)
			throw new LiquidoException(LiquidoException.Errors.INVALID_VOTER_TOKEN, "You are not allowed to cast a vote.");

		// Delete the consumed voterToken
		voterToken.delete();

		return voterToken.getRightToVote();
	}

	/**
	 * Hash a plain voter token. Server will add an internal hashSecret for more security.
	 * Keep in mind that the hashedVoterToken is anonymous. It is not traceable back to a voter.
	 *
	 * @param plainVoterToken the plain token
	 * @param pollId voter token is only valid for this poll
	 * @return the hashed voterToken
	 */
	private String calcHashedVoterToken(String plainVoterToken, Long pollId) {
		return DigestUtils.sha3_256Hex(plainVoterToken + pollId + config.hashSecret());
	}


	/**
	 * User casts their own vote. Keep in mind that this method is called anonymously. No UserEntity involved.
	 * If that user is a proxy for other voters, then their ballots will also be added automatically.
	 *
	 * @param plainVoterToken The anonymous voter must present a valid plainVoterToken that he fetched via {@link #createOneTimeVoterToken(UserEntity, PollEntity)}
	 * @param poll the poll to cast the vote in.
	 * @param voteOrderIds ordered list of proposal.IDs as sorted by the user. No ID may appear more than once!
	 * @return CastVoteResponse with ballot and the voteCount how often the vote was actually counted for this proxy. (Some voters might already have voted on their own.)
	 * @throws LiquidoException when voterToken is invalid or there is <b>anything</b> suspicious with the ballot
	 */
	@Transactional
	public CastVoteResponse castVote(String plainVoterToken, PollEntity poll, List<Long> voteOrderIds) throws LiquidoException {
		//TODO: For even more security we could implement a challenge response mechanism for verifying plainVoterToken
		log.info("castVote(poll={}, voteOrderIds={})", poll, voteOrderIds);

		// CastVoteRequest must contain a poll
		if (poll == null || poll.getId() == null)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Need poll to cast vote");

		// Poll must be in status voting
		if (!PollEntity.PollStatus.VOTING.equals(poll.getStatus()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Poll must be in status VOTING");

		// voterOrder must contain at least one element
		if (voteOrderIds == null || voteOrderIds.isEmpty())
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Need voteOrder to cast vote");

		// Convert voteOrderIds to list of actual ProposalEntities from poll.
		// Therefore, voteOrderIds must only contain proposal.ids from this poll and it must not not contain any ID more than once!
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

		// Validate voter token and lookup linked RightToVote
		RightToVoteEntity rightToVote = consumeVoterToken(plainVoterToken, poll);

		// Create a new ballot for the voter himself at level 0.
		BallotEntity newBallot = new BallotEntity(poll, 0, voteOrder, rightToVote);

		// check this ballot and recursively cast ballots for delegated rightToVotes
		return castVoteRec(newBallot);
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
	 *      IF the level of the existing ballot is SMALLER than the passed newBallot.level
	 *      THEN do NOT update the existing ballot, because it was cast by a lower proxy or the voter himself
	 *      ELSE update the existing ballot's level and vote order
	 *
	 *  3) FOR EACH directly delegated RightToVote
	 *              build a childBallot and recursively cast this childBallot.
	 *
	 *  Remark: The child ballot might not be stored when there already is one with a smaller level. This is
	 *          our recursion limit.
	 *
	 * @param newBallot the ballot that shall be stored. The ballot will be checked very thoroughly. Especially if the ballot's right to vote is valid.
	 * @return the newly created or updated existing ballot OR
	 *         null if the ballot wasn't stored due to an already existing ballot with a smaller level.
	 */
	//@Transactional Do not open a transaction for each recursion!
	private CastVoteResponse castVoteRec(BallotEntity newBallot) throws LiquidoException {
		log.debug("   castVoteRec: {}", newBallot);

		//----- check the validity of the ballot
		checkBallot(newBallot);

		//----- check if there already is a ballot, then update that, otherwise save newBallot
		Optional<BallotEntity> existingBallotOpt = BallotEntity.findByPollAndRightToVote(newBallot.getPoll(), newBallot.getRightToVote());
		BallotEntity savedBallot;

		if (existingBallotOpt.isPresent()) {
			//----- Update existing ballot if the level of newBallot is smaller.  Proxy must not overwrite a voter's own vote OR a vote from a proxy below him
			BallotEntity existingBallot = existingBallotOpt.get();
			if (existingBallot.getLevel() < newBallot.getLevel()) {
				log.debug("   Voter has already voted for himself {}", existingBallot);
				return null;
			}
			log.debug("  Update existing ballot {}", existingBallot.id);
			existingBallot.setVoteOrder(newBallot.getVoteOrder());
			existingBallot.setLevel(newBallot.getLevel());
			existingBallot.persist();
			savedBallot = existingBallot;
		} else {
			//----- If there is no existing ballot yet with that rightToVote, then builder a completely new one.
			log.debug("   Saving new ballot");
			newBallot.persist();
			savedBallot = newBallot;
		}

		//----- When a user is a proxy, then recursively cast a ballot for each delegated rightToVote
		long voteCount = 0;   // count for how many delegees (that have not voted yet for themselves) the proxy's ballot is also cast
		for (RightToVoteEntity delegatedRightToVote : savedBallot.rightToVote.delegations) {
			List<ProposalEntity> voteOrderClone = new ArrayList<>(newBallot.getVoteOrder());   // BUGFIX for org.hibernate.HibernateException: Found shared references to a collection
			BallotEntity childBallot = new BallotEntity(newBallot.getPoll(), newBallot.getLevel() + 1, voteOrderClone, delegatedRightToVote);
			log.debug("   Proxy casts vote for delegated childBallot {}", childBallot);
			CastVoteResponse childRes = castVoteRec(childBallot);  // will return null when level of an existing childBallot is smaller than the childBallot that the proxy would cast. => this ends the recursion
			if (childRes != null) voteCount += 1 + childRes.getVoteCount();
		}

		// voteCount does NOT include the voters (or proxies) own ballot
		return new CastVoteResponse(savedBallot, voteCount);
	}

	/**
	 * Check if a ballot is valid before we store it
	 * @param ballot a cast vote with a sorted voteOrder inside.
	 * @throws LiquidoException when something inside ballot is invalid
	 */
	public void checkBallot(BallotEntity ballot) throws LiquidoException {
		// check that poll is actually in voting phase and has at least two alternative proposals
		PollEntity poll = ballot.getPoll();
		if (!PollEntity.PollStatus.VOTING.equals(poll.getStatus())) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Poll must be in voting phase.");
		}
		if (poll.getProposals().size() < 2)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Poll must have at least two alternative proposals.");

		// check that voter Order is not empty
		if (ballot.getVoteOrder().isEmpty()) {
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

		// check that all proposals you want to vote for are in this poll and that they are also in voting phase
		for(ProposalEntity proposal : ballot.getVoteOrder()) {
			if (!proposal.getPoll().getId().equals(ballot.getPoll().getId()))   //BUGFIX: Cannot compare whole poll. Must compare IDs:  https://hibernate.atlassian.net/browse/HHH-3799  PersistentSet does not honor hashcode/equals contract when loaded eagerly
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: The Proposal(id="+proposal.getId()+") from your voteOrder is not part of poll(id="+ballot.getPoll().getId()+")!");
			if (!ProposalEntity.LawStatus.VOTING.equals(proposal.getStatus())) {
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: proposals must be in voting phase.");
			}
		}

		// Every ballot must be linked to an existing & persisted RightToVote
		RightToVoteEntity rightToVote = RightToVoteEntity.findByHash(ballot.getRightToVote().hashedVoterInfo)
				.orElseThrow(() -> new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Ballot must be linked to an existing RightToVote."));
	}


	//TODO: automatically refresh RightToVote, e.g. when vote is casted
	/**
	 * Refresh the expiration time of this valid rightToVote.
	 * @param rightToVote the voter's encoded right to vote
	 */
	@Transactional
	public void refreshRightToVote(RightToVoteEntity rightToVote) {
		rightToVote.setExpiresAt(LocalDateTime.now().plusHours(config.rightToVoteExpirationDays()));
		rightToVote.persist();
	}

}