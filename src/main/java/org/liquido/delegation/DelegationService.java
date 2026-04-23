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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

		// A voter must first request a delegation to a proxy, because he will know how his proxy voted.
		// Proxies can decide to be a public proxy and accept every delegation request immediately.
		if (proxyRightToVote.getPublicProxy() != null) {
			log.info("Delegation: {} requests delegation to proxy {}", currentUser.toStringShort(), proxy.toStringShort());
			DelegationEntity.buildDelegationRequest(currentUser, proxy, usersRightToVote);
		} else {
			log.info("Delegation: {} delegates to proxy {}", currentUser.toStringShort(), proxy.toStringShort());
			usersRightToVote.delegateToProxy(proxyRightToVote);
		}

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

	public List<DelegationEntity> getDelegationRequests() throws LiquidoException {
		UserEntity proxy = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Must be logged in to get your delegation requests"));
		return DelegationEntity.findDelegationRequestsTo(proxy);
	}

	public void acceptDelegationRequests(List<Long> delegationRequestIds) throws LiquidoException {
		UserEntity proxy = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Must be logged in to accept delegation requests"));
		RightToVoteEntity proxyRightToVote = RightToVoteEntity.findByVoter(proxy, config.hashSecret())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "Cannot delegate to Proxy. Cannot find RightToVote"));

		DelegationEntity.findByIds(delegationRequestIds).forEach(delegationRequest -> {
			RightToVoteEntity requestedDelegationFrom = delegationRequest.getRequestedDelegationFrom();
			if (requestedDelegationFrom == null) return;
			requestedDelegationFrom.delegateToProxy(proxyRightToVote);
		});
	}

	/**
	 * Count how many voters delegate to this proxy, including transitive delegations.
	 */
	public long countDelegationsTo(@NonNull UserEntity proxy) throws LiquidoException {
		RightToVoteEntity proxyRightToVote = RightToVoteEntity.findByVoter(proxy, config.hashSecret())
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "Cannot count delegations. Cannot find RightToVote for proxy."));
		return countDelegationsRec(proxyRightToVote, new HashSet<>());
	}

	private long countDelegationsRec(RightToVoteEntity proxyRightToVote, Set<String> visited) {
		if (!visited.add(proxyRightToVote.getHashedVoterInfo())) return 0;

		long count = 0;
		for (RightToVoteEntity delegatedRightToVote : proxyRightToVote.getDelegations()) {
			count += 1 + countDelegationsRec(delegatedRightToVote, visited);
		}
		return count;
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