package org.liquido.delegation;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import java.util.List;

/**
 * In LIQUIDO, a voter can delegate his right to vote to a proxy
 */
@GraphQLApi
public class DelegationGraphQL {

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	LiquidoConfig config;

	@Inject
	DelegationService delegationService;

	@Mutation
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Description("Delegate your right to vote to a proxy.")
	@Transactional
	public void delegateTo(
			@Name("proxyId") @NonNull Long proxyId
	) throws LiquidoException {
		UserEntity proxy = UserEntity.<UserEntity>findByIdOptional(proxyId)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_ASSIGN_PROXY, "Cannot find proxy with id="+proxyId));
		delegationService.delegateTo(proxy);
	}

	@Mutation
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Description("Remove your current delegation to a proxy.")
	@Transactional
	public void removeDelegation() throws LiquidoException {
		delegationService.removeDelegation();
	}

	@Query
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Description("Get delegation requests that are waiting for your acceptance.")
	public List<DelegationEntity> getDelegationRequests() throws LiquidoException {
		return delegationService.getDelegationRequests();
	}

	@Mutation
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Description("Accept pending delegation requests by their IDs.")
	@Transactional
	public void acceptDelegationRequests(@NonNull List<Long> delegationRequestIds) throws LiquidoException {
		delegationService.acceptDelegationRequests(delegationRequestIds);
	}

	@Query
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Description("Count how many voters delegate to a proxy, including transitive delegations.")
	public long delegationCount(@Name("proxyId") @NonNull Long proxyId) throws LiquidoException {
		UserEntity proxy = UserEntity.<UserEntity>findByIdOptional(proxyId)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_FIND_USER, "Cannot find proxy with id=" + proxyId));
		return delegationService.countDelegationsTo(proxy);
	}


}