package org.liquido;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.liquido.poll.PollEntity;
import org.liquido.poll.PollService;
import org.liquido.poll.ProposalEntity;
import org.liquido.security.JwtTokenUtils;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoException;
import org.liquido.vote.RightToVoteEntity;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@QuarkusTest
public class EqualityTests {

	@Inject
	PollService pollService;

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Test
	public void twoNotYetPersistedUsersUserEntities_ShouldEqualByEmailOnly() {
		UserEntity user1 = new UserEntity("DummyName_A", "dummy_A@email.de", "dummyPasswordHash_A");
		UserEntity user2 = new UserEntity("DummyName_A", "dummy_A@email.de", "dummyPasswordHash_B");
		assertNotEquals(user1, user2, "Not new not yet persisted user entities (even with same email and mobilephone) should NOT be equal!");
	}

	@Test
	public void twoEmptyUserEntities_ShouldNotBeEqual() {
		UserEntity t1 = new UserEntity();
		UserEntity t2 = new UserEntity();
		assertNotEquals(t1, t2, "two empty unsaved UserEntities (without ID) should not equal");
	}

	@Test
	@TestTransaction
	public void twoInstancesOfSamePersitedUserEntity_ShouldBeEqual() {
		UserEntity user1 = new UserEntity("DummyName_A", "dummy_A@email.de", "dummyPasswordHash_A");
		user1.persist();
		assertNotNull(user1.id, "Persisted UserEntity MUST have an ID!");

		UserEntity user2 = UserEntity.findById(user1.id);
		assertEquals(user1, user2, "Two instances of same persisted user should equal!");
	}

	@Test
	@TestTransaction
	public void twoPersistedUserEntitiesEvenWithSameData_ShouldNotBeEqual() {
		UserEntity user1 = new UserEntity("DummyName_A", "dummy_A@email.de", "dummyPasswordHash_A");
		UserEntity user2 = new UserEntity("DummyName_A", "dummy_A@email.de", "dummyPasswordHash_B");
		user1.persist();
		user2.persist();

		assertNotNull(user1.getId(), "TestEntity1 should now have an ID");
		assertNotNull(user2.getId(), "TestEntity2 should now have an ID");
		assertNotEquals(user1, user2, "Two persisted UserEntities should NOT be equal, even with same data");
	}

	@Test
	@TestTransaction
	public void twoUnsavedProposals_ShouldNotBeEqual() {
		ProposalEntity p1 = new ProposalEntity("Prop Title", "Prop Description");
		ProposalEntity p2 = new ProposalEntity("Prop Title", "Prop Description");
		assertNotEquals(p1, p2, "two unsaved Proposals (without ID) should NOT be equal.");
	}

	@Test
	@TestTransaction
	public void twoDifferentPersistedProposals_ShouldNotBeEqual() {
		ProposalEntity p1 = new ProposalEntity("Prop Title 1", "Prop1 Description");
		ProposalEntity p2 = new ProposalEntity("Prop Title 2", "Prop2 Description");
		p1.persist();
		p2.persist();
		assertNotNull(p1.getId(), "Proposal1 should now have an ID");
		assertNotNull(p2.getId(), "Proposal2 should now have an ID");
		assertNotEquals(p1, p2, "two different saved ProposalEntities should NOT be equal.");
	}

	@Test
	@TestTransaction
	public void twoBallotsWithSameVoterToken() {
		String hasehdVoterToken1 = "superCoolHash1";
		RightToVoteEntity rightToVote1 = new RightToVoteEntity(hasehdVoterToken1);
		String hasehdVoterToken2 = "superCoolHash2";
		RightToVoteEntity rightToVote2 = new RightToVoteEntity(hasehdVoterToken2);
		assertNotEquals(rightToVote1, rightToVote2, "two RightToVoteEntity with different hashedVoterToken should not be equal!");
	}

	@Test
	@TestTransaction
	@TestSecurity(user = TestFixtures.staticDummyEmail, roles = {JwtTokenUtils.LIQUIDO_ADMIN_ROLE, JwtTokenUtils.LIQUIDO_USER_ROLE})
	@JwtSecurity(claims = {
			@Claim(key = "email", value = TestFixtures.staticDummyEmail),
			@Claim(key = "groups", value = JwtTokenUtils.LIQUIDO_ADMIN_ROLE)
	})
	public void testCollectionBehaviour() throws LiquidoException {
		// Dummy login
		UserEntity user = LiquidoTestUtils.getRandomUser();
		jwtTokenUtils.setCurrentUserAndTeam(user, null);

		ProposalEntity p1 = new ProposalEntity("Prop1 Title", "Prop1 Description");
		p1.setStatus(ProposalEntity.LawStatus.PROPOSAL);
		p1.persist();
		ProposalEntity p2 = new ProposalEntity("Prop2 Title", "Prop2 Description");
		p2.setStatus(ProposalEntity.LawStatus.PROPOSAL);
		p2.persist();

		PollEntity poll = new PollEntity("Test Poll");

		poll = pollService.addProposalToPoll(p1, poll);
		poll = pollService.addProposalToPoll(p2, poll);

		assertTrue(poll.getProposals().contains(p1), "Poll.proposals should contain proposal1!");
		assertTrue(poll.getProposals().contains(p2), "Poll.proposals should contain proposal2!");
	}

	@Test
	@TestTransaction
	@TestSecurity(user = TestFixtures.staticDummyEmail, roles = {JwtTokenUtils.LIQUIDO_ADMIN_ROLE, JwtTokenUtils.LIQUIDO_USER_ROLE})
	@JwtSecurity(claims = {
			@Claim(key = "email", value = TestFixtures.staticDummyEmail),
			@Claim(key = "groups", value = JwtTokenUtils.LIQUIDO_ADMIN_ROLE)
	})
	public void testCastVotesInTwoPolls() throws LiquidoException {
		UserEntity user = LiquidoTestUtils.getRandomUser();
		jwtTokenUtils.setCurrentUserAndTeam(user, null);

		PollEntity.find("status=PollStatus.VOTING");

	}

	}