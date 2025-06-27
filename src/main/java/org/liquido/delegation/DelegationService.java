package org.liquido.delegation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.JwtTokenUtils;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;
import org.liquido.vote.RightToVoteEntity;

@Slf4j
@ApplicationScoped
public class DelegationService {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	LiquidoConfig config;

	/**
	 * A voter delegates his RightToVote to a proxy.
	 * Then the proxy will cast ballots for him.
	 * @param proxy another voter
	 * @throws LiquidoException when proxy equals user or delegation would cause a circle.
	 */
	@Transactional
	public void delegateTo(@NonNull UserEntity proxy) throws LiquidoException {
		// User
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Must be logged in to delegate."));
		RightToVoteEntity usersRightToVote = RightToVoteEntity.findByVoter(currentUser, config.hashSecret())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "Cannot delegate to Proxy. Cannot find RightToVote"));

		// proxy
		RightToVoteEntity proxyRightToVote = RightToVoteEntity.findByVoter(proxy, config.hashSecret())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "Cannot delegate to Proxy. Cannot find RightToVote"));

		if (proxy.equals(currentUser))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "You cannot delegate to yourself!");

		if (delegationWouldCauseCycle(usersRightToVote, proxyRightToVote))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ASSIGN_CIRCULAR_PROXY, "Delegation to this proxy would cause a circle. This proxy or one of his proxies already delegate his RightToVote to you. You can already vote for this user.");

		log.info("Delegation: {} delegates to {}", currentUser.toStringShort(), proxy.toStringShort());
		usersRightToVote.delegateToProxy(proxyRightToVote);

		//TODO:  HIGH PRIO MUST:  A voter must request a delegation! Otherwise he could know the vote of the proxy.
		//DelegationEntity.buildDelegationRequest(currentUser, proxy, )

		proxyRightToVote.persist();
		usersRightToVote.persist();
	}

	/**
	 * Remove the delegation to a proxy.
	 * @throws LiquidoException when user has no RightToVote, which should never happen
	 */
	@Transactional
	public void removeDelegation() throws LiquidoException {
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Must be logged in to remove delegation"));
		RightToVoteEntity usersRightToVote = RightToVoteEntity.findByVoter(currentUser, config.hashSecret())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_REMOVE_PROXY, "Cannot remove delegation, you have no right to vote!"));
		RightToVoteEntity proxiesRightToVote = usersRightToVote.getDelegatedTo();
		if (proxiesRightToVote == null) return;

		log.info("Delegations: {} removes his delegation", currentUser.toStringShort());  // We don't know the proxy user here.
		usersRightToVote.removeDelegationToProxy();
		proxiesRightToVote.persist();
		usersRightToVote.persist();
	}

	/**
	 * Check if adding this delegation would create a cycle.
	 */
	public boolean delegationWouldCauseCycle(RightToVoteEntity usersRightToVote, RightToVoteEntity proxy) {
		RightToVoteEntity current = proxy;
		while (current != null) {
			if (usersRightToVote.equals(current)) return true;
			current = current.getDelegatedTo();
		}
		return false;
	}
}