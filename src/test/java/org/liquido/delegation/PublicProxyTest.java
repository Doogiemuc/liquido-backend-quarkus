package org.liquido.delegation;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>A public proxy accepts delegations without being asked</h1>
 *
 * Delegating costs the proxy privacy, not the delegee: once a proxy votes, every delegee reads the
 * ballot cast for them and therefore learns exactly how their proxy voted (whitepaper 10.4). That is
 * why a delegation is normally a REQUEST the proxy has to accept.
 *
 * A public proxy waives that, once, in advance - which is what makes "the party's position-holder"
 * workable: a party with ten thousand members cannot accept ten thousand requests by hand
 * (whitepaper 10.3).
 *
 * <p>The observable difference is therefore not cosmetic. For an ordinary proxy a delegation
 * produces a pending request and no edge; for a public proxy it produces an edge and no request.
 * This test pins both directions, because until the {@code becomePublicProxy} mutation existed
 * nothing could set the flag at all - the auto-accept branch in {@link DelegationService#delegateTo}
 * was unreachable in production and only ever exercised by a test that set the field directly.
 */
@QuarkusTest
@DisplayName("A public proxy auto-accepts delegations; withdrawing that restores the request flow")
public class PublicProxyTest {

	@Inject
	LiquidoTestUtils util;

	@Test
	@DisplayName("Delegating to a public proxy creates the edge immediately, with no request to accept")
	public void delegationToAPublicProxyIsAcceptedAutomatically() {
		// GIVEN a proxy who has declared himself public, and a member of his team
		TeamDataResponse team = util.createFreshTeam("PublicProxy");
		UserEntity proxy = util.joinTeam(team.team.getInviteCode(), null).user;
		UserEntity delegee = util.joinTeam(team.team.getInviteCode(), null).user;

		String proxyJwt = util.devLogin(proxy.email).jwt;
		util.becomePublicProxy(true, proxyJwt);

		// WHEN the member delegates to him
		util.delegateTo(proxy, util.devLogin(delegee.email).jwt);

		// THEN the delegation counts right away ...
		proxyJwt = util.devLogin(proxy.email).jwt;
		assertEquals(1, util.getDelegationCount(proxy, proxyJwt),
				"A delegation to a PUBLIC proxy must take effect immediately, without being accepted.");

		// ... and there is nothing left for him to accept.
		assertTrue(util.getDelegationRequestIds(proxyJwt).isEmpty(),
				"A public proxy must not be asked to accept what he already accepted by being public.");
	}

	@Test
	@DisplayName("Withdrawing public-proxy status makes later delegations wait for acceptance again")
	public void withdrawingPublicProxyRestoresTheRequestFlow() {
		// GIVEN a public proxy who already collected one automatic delegation
		TeamDataResponse team = util.createFreshTeam("UnPublicProxy");
		UserEntity proxy = util.joinTeam(team.team.getInviteCode(), null).user;
		UserEntity early = util.joinTeam(team.team.getInviteCode(), null).user;
		UserEntity late = util.joinTeam(team.team.getInviteCode(), null).user;

		String proxyJwt = util.devLogin(proxy.email).jwt;
		util.becomePublicProxy(true, proxyJwt);
		util.delegateTo(proxy, util.devLogin(early.email).jwt);

		// WHEN he stops being a public proxy
		proxyJwt = util.devLogin(proxy.email).jwt;
		util.becomePublicProxy(false, proxyJwt);

		// AND somebody else delegates to him afterwards
		util.delegateTo(proxy, util.devLogin(late.email).jwt);

		// THEN that later delegation only REQUESTS, it does not take effect ...
		proxyJwt = util.devLogin(proxy.email).jwt;
		List<Long> pending = util.getDelegationRequestIds(proxyJwt);
		assertFalse(pending.isEmpty(),
				"Once public-proxy status is withdrawn, a delegation must go back to needing acceptance.");

		// ... while the delegation accepted while he WAS public stays untouched. Withdrawing the
		// status is not a way to shed delegees: undoing a delegation belongs to the voter who made it.
		assertEquals(1, util.getDelegationCount(proxy, proxyJwt),
				"Withdrawing public-proxy status must not revoke delegations already accepted.");

		// AND accepting the pending one still works, so nothing is stuck
		util.acceptDelegationRequests(pending, proxyJwt);
		proxyJwt = util.devLogin(proxy.email).jwt;
		assertEquals(2, util.getDelegationCount(proxy, proxyJwt),
				"A request pending from the non-public period must still be acceptable.");
	}
}
