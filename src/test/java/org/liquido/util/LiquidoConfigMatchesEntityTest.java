package org.liquido.util;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.poll.ProposalEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * <h1>The config the frontend fetches must match what the entities actually enforce</h1>
 *
 * {@link ConfigGraphQL} serves {@code proposalDescriptionMinLength} to the frontend, which uses it
 * to decide when the Save button lights up. The value that actually <i>rejects</i> a description is
 * the {@code @Size(min = ...)} on {@link ProposalEntity#description}. Those are two different
 * numbers in two different files, and nothing but this test makes them agree.
 *
 * <h2>Why this test exists</h2>
 *
 * It had already gone wrong. The frontend's own copy said 10 while the entity said 20, so a
 * description of 12 characters passed every check the user could see and was then refused by the
 * server. Moving the value into config fixes the frontend copy but recreates the same hazard one
 * level down - unless something asserts it. So: encode the invariant.
 *
 * Same reasoning as {@link ErrorCodesInSyncTest}, which locks the error codes to the frontend copy.
 */
@QuarkusTest
public class LiquidoConfigMatchesEntityTest {

	@Inject
	LiquidoConfig config;

	@Test
	@DisplayName("proposalDescriptionMinLength must equal ProposalEntity.description's @Size(min)")
	public void descriptionMinLengthMatchesEntity() throws NoSuchFieldException {
		Size size = ProposalEntity.class.getDeclaredField("description").getAnnotation(Size.class);
		assertNotNull(size, "ProposalEntity.description must keep its @Size annotation - it is what "
				+ "actually enforces the minimum. Without it the config value promises a rule nobody applies.");
		assertEquals(size.min(), config.proposalDescriptionMinLength(),
				"liquido.proposal-description-min-length is served to the frontend and decides when its "
				+ "Save button enables. If it disagrees with @Size(min) on the entity, users fill in a form "
				+ "the client accepts and the server then rejects. Change both, or neither.");
	}

	@Test
	@DisplayName("The shared minimum lengths are all positive")
	public void minimumsArePositive() {
		// A zero or negative minimum would silently disable a validation rule in the frontend, which
		// trusts these numbers without sanity checking them.
		assertEquals(true, config.usernameMinLength() > 0, "usernameMinLength must be positive");
		assertEquals(true, config.pollTitleMinLength() > 0, "pollTitleMinLength must be positive");
		assertEquals(true, config.proposalTitleMinLength() > 0, "proposalTitleMinLength must be positive");
		assertEquals(true, config.proposalDescriptionMinLength() > 0, "proposalDescriptionMinLength must be positive");
		assertEquals(true, config.inviteCodeLength() > 0, "inviteCodeLength must be positive");
		assertEquals(true, config.minPasswordLength() > 0, "minPasswordLength must be positive");
	}
}
