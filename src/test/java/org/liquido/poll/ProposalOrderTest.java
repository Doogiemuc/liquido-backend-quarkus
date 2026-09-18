package org.liquido.poll;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.team.TeamDataResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <h1>A poll's proposals come back in a stable order</h1>
 *
 * {@link PollEntity#getProposals()} is a {@code Set}. Without {@code @OrderBy} its iteration order
 * is the HashSet's, which is derived from {@link ProposalEntity#hashCode()} -
 * {@code @EqualsAndHashCode(of={"title","status"})}. Status is rewritten to LOST/LAW the moment
 * {@code PollService.finishVotingPhase()} runs, so the order a caller saw before a poll closed was
 * not the order it saw afterwards, and neither was reproducible.
 *
 * <p>That is not merely untidy. The ballot a voter sorts is this collection, and
 * {@code calcWinnerOfPoll()} sorts the duel matrix axes by id precisely because it could not rely on
 * it. This pins the collection itself down so the two agree.
 */
@QuarkusTest
@DisplayName("A poll's proposals iterate in id order, whatever the poll's status")
public class ProposalOrderTest {

	@Inject
	LiquidoTestUtils util;

	@Test
	@DisplayName("getProposals() is ordered by id, and stays so after the statuses change")
	public void proposalsIterateInIdOrder() {
		// GIVEN a poll whose proposals were deliberately NOT added in an order that matches their
		// titles, so a title-derived hash order and an id order cannot coincide by luck
		TeamDataResponse team = util.createFreshTeam("ProposalOrder");
		long unique = System.currentTimeMillis();
		PollEntity poll = util.createPoll("Proposal order poll " + unique, team.jwt);
		String desc = "Long enough description to clear the backend's @Size(min=20) check. " + unique;
		util.addProposal(poll.getId(), "zzz first added " + unique, desc, "bolt", team.jwt);
		util.addProposal(poll.getId(), "mmm second added " + unique, desc, "bolt", team.jwt);
		util.addProposal(poll.getId(), "aaa third added " + unique, desc, "bolt", team.jwt);

		// WHEN the poll is read back from the database
		PollEntity reloaded = PollEntity.findById(poll.id);

		// THEN its proposals iterate in id order - ie. the order they were added in
		List<Long> ids = new ArrayList<>();
		reloaded.getProposals().forEach(p -> ids.add(p.getId()));

		List<Long> sorted = new ArrayList<>(ids);
		sorted.sort(Long::compareTo);
		assertEquals(sorted, ids,
				"a poll's proposals must iterate in id order, not in HashSet order - the ballot a voter " +
				"sorts IS this collection, and ProposalEntity.hashCode() changes with its status");
	}
}
