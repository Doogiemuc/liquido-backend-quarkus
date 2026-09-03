package org.liquido.delegation;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamEntity;
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
		// A delegation edge can only ever join two rights to vote in the SAME team, because that is the
		// only scope a right to vote exists in. Delegation never crosses a team boundary -- a proxy in
		// one team has no standing in another team's poll -- so this is the domain shape, not a limit.
		TeamEntity team = currentTeam();
		RightToVoteEntity usersRightToVote = RightToVoteEntity.findByVoterAndTeam(currentUser, team, config)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "Cannot delegate to Proxy. Cannot find his RightToVote"));

		// proxy
		RightToVoteEntity proxyRightToVote = RightToVoteEntity.findByVoterAndTeam(proxy, team, config)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "Cannot delegate to Proxy. Cannot find RightToVote"));

		if (proxy.equals(currentUser))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "You cannot delegate to yourself!");

		if (delegationWouldCauseCycle(usersRightToVote, proxyRightToVote))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_ASSIGN_CIRCULAR_PROXY, "Delegation to this proxy would cause a circle. This proxy or one of his proxies already delegate his RightToVote to you. You can already vote for this user.");

		// A voter must first request a delegation to a proxy, because he will know how his proxy voted.
		// Proxies can decide to be a public proxy and accept every delegation request immediately.
		if (proxyRightToVote.getPublicProxy() != null) {
			log.info("Delegation: {} delegates to public proxy {}", currentUser.toStringShort(), proxy.toStringShort());
			usersRightToVote.delegateToProxy(proxyRightToVote);
		} else {
			log.info("Delegation: {} requests delegation to proxy {}", currentUser.toStringShort(), proxy.toStringShort());
			DelegationEntity.createDelegationRequest(currentUser, proxy, usersRightToVote);
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
		RightToVoteEntity usersRightToVote = RightToVoteEntity.findByVoterAndTeam(currentUser, currentTeam(), config)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_REMOVE_PROXY, "Cannot remove delegation, you have no right to vote!"));

		// Delete the DelegationEntity row (pending request or accepted record) too, otherwise it's left
		// behind occupying the fromuser_id unique slot and misreporting this user's delegation status.
		DelegationEntity.findByFromUser(currentUser).ifPresent(PanacheEntityBase::delete);

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

	@Transactional
	public void acceptDelegationRequests(List<Long> delegationRequestIds) throws LiquidoException {
		UserEntity proxy = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Must be logged in to accept delegation requests"));
		RightToVoteEntity proxyRightToVote = RightToVoteEntity.findByVoterAndTeam(proxy, currentTeam(), config)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "Cannot delegate to Proxy. Cannot find RightToVote"));

		// SECURITY: only ever accept requests that were actually addressed to this proxy (findDelegationRequestsTo
		// already filters on toProxy=this proxy), then filter down to the requested IDs. Previously this used
		// findByIdList(delegationRequestIds) directly, which fetched ANY delegation rows by arbitrary ID with no
		// check they were addressed to the caller -- e.g. acceptDelegationRequests([1..1000]) absorbed every
		// pending delegation request in the system.
		List<DelegationEntity> toAccept = DelegationEntity.findDelegationRequestsTo(proxy).stream()
				.filter(delegationRequest -> delegationRequestIds.contains(delegationRequest.getId()))
				.toList();

		// A plain for loop, not forEach: accepting has to be able to REFUSE, and a lambda cannot throw
		// the checked exception that refusal needs.
		for (DelegationEntity delegationRequest : toAccept) {
			RightToVoteEntity requestedDelegationFrom = delegationRequest.getRequestedDelegationFrom();

			// SECURITY/CORRECTNESS: the cycle check on delegateTo() is NOT enough on its own. When a
			// delegation to a non-public proxy is only REQUESTED, no edge exists yet -- so two users can
			// each request the other with neither request looking circular at request time, and the cycle
			// is created here, at accept time, by the second accept. Two cooperating users could
			// therefore build a loop that makes vote casting and every upward walk of the graph recurse
			// forever. The edge is created here, so the check belongs here too.
			if (delegationWouldCauseCycle(requestedDelegationFrom, proxyRightToVote))
				throw new LiquidoException(LiquidoException.Errors.CANNOT_ASSIGN_CIRCULAR_PROXY,
						"Cannot accept this delegation request: it would create a circular delegation.");

			requestedDelegationFrom.delegateToProxy(proxyRightToVote);
			// Clear the request markers rather than deleting the row: the fromuser_id unique constraint
			// means this row also IS the record of the now-accepted delegation (see isDelegationRequest()).
			delegationRequest.setRequestedDelegationFrom(null);
			delegationRequest.setRequestedDelegationAt(null);
			delegationRequest.persist();
		}
		proxyRightToVote.persist();
	}

	/**
	 * Count how many voters delegate to this proxy, including transitive delegations.
	 */
	public long countDelegationsTo(@NonNull UserEntity proxy) throws LiquidoException {
		RightToVoteEntity proxyRightToVote = RightToVoteEntity.findByVoterAndTeam(proxy, currentTeam(), config)
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


	/** The team the caller is logged into -- the scope every right to vote here lives in. */
	private TeamEntity currentTeam() throws LiquidoException {
		return jwtTokenUtils.getCurrentTeam()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED, "Must be logged into a team to manage delegations."));
	}

	/**
	 * Check if adding this delegation would create a cycle.
	 */
	public boolean delegationWouldCauseCycle(RightToVoteEntity usersRightToVote, RightToVoteEntity proxy) {
		// The visited set guards the DETECTOR itself. Walking delegatedTo with only a null check
		// terminates on a well-formed graph, but hangs forever on one that already contains a loop --
		// so the one function whose job is to find loops was the one that could not survive meeting one.
		Set<String> visited = new HashSet<>();
		RightToVoteEntity current = proxy;
		while (current != null) {
			if (usersRightToVote.equals(current)) return true;
			if (!visited.add(current.getHashedVoterInfo())) return true;   // already looping: refuse
			current = current.getDelegatedTo();
		}
		return false;
	}
}