package org.liquido;

import io.quarkus.test.junit.QuarkusTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;

import javax.transaction.Transactional;
import java.util.Date;

@Slf4j
@QuarkusTest
class UserEntityTest {

	@BeforeEach
	void setUp() {
	}

	@Transactional
	UserEntity createTestUser() {
		Long now = new Date().getTime();
		UserEntity user = new UserEntity(
				"TestUser" + now,
				"testuser"+now+"@liquido.vote",
				"+49 555 " + now
		);
		user.persist();
		return user;
	}

	@Test
	@Transactional
	void testCreateUser() {
		Long now = new Date().getTime();
		UserEntity user = new UserEntity(
				"TestUser" + now,
				"testuser"+now+"@liquido.vote",
				"+49 555 " + now
		);
		user.persist();
		log.info("Successfully created " + user);
	}

	@Test
	@Transactional
	void testCreateTeam() {
		UserEntity admin = createTestUser();
		log.info("Admin " + admin);
		Long now = new Date().getTime();
		TeamEntity team = new TeamEntity("TestTeam"+now, admin);
		team.persist();
		log.info("Successfully created " + team);
	}



}