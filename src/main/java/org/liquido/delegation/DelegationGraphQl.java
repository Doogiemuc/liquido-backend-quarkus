package org.liquido.delegation;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

/**
 * In LIQUIDO, a voter can delegate his right to vote to a proxy
 */
@GraphQLApi
public class DelegationGraphQl {

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


}