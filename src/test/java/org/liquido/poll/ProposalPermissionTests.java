package org.liquido.poll;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.hamcrest.core.DescribedAs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.TestFixtures;
import org.liquido.team.TeamDataResponse;
import org.liquido.util.Lson;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.liquido.TestFixtures.JQL_POLL;

/**
 * <h1>Who may add proposals, and who may edit them</h1>
 *
 * Two rules introduced on 2026-08-15:
 * <ul>
 *   <li><b>Adding</b> is a per-poll setting the admin chooses at creation:
 *       {@code membersCanAddProposals}. Off by default - then only the admin may add. On - every
 *       member may add, as many as they like. (This replaced a hard-coded "one proposal per member
 *       per poll" rule.)</li>
 *   <li><b>Editing</b> is always limited to your own proposal, and only while the poll is still in
 *       ELABORATION.</li>
 * </ul>
 *
 * Everything here runs on its <b>own fresh teams</b>. Editing rewrites an existing row's content,
 * which the seed contract in {@link LiquidoTestUtils} forbids for seed rows - and other tests match
 * on the seeded {@code Test Proposal N_4711…} titles.
 */
@QuarkusTest
public class ProposalPermissionTests {

	@Inject
	LiquidoTestUtils util;

	// ==================== helpers ====================

	/** Send a GraphQL request and expect it to be REJECTED with the given LiquidoException name. */
	private void assertRejected(String query, Lson vars, String jwt, String expectedErrorName, String because) {
		ValidatableResponse res = given()
				.contentType(ContentType.JSON)
				.header("Authorization", "Bearer " + jwt)
				.body(String.format("{ \"query\": \"%s\", \"variables\": %s }", query, vars))
				.when()
				.post(TestFixtures.GRAPHQL_URI)
				.then()
				.statusCode(200);   // GraphQL always answers 200; errors live in the body
		res.body("errors[0].extensions.liquidoException.liquidoErrorName",
				DescribedAs.describedAs(because, is(expectedErrorName)));
	}

	private static final String ADD_PROPOSAL_QUERY =
			"mutation addProposal($pollId: BigInteger!, $title: String!, $description: String!, $icon: String!) { " +
			"addProposal(pollId: $pollId, title: $title, description: $description, icon: $icon) " + JQL_POLL + "}";

	private static final String UPDATE_PROPOSAL_QUERY =
			"mutation updateProposal($pollId: BigInteger!, $proposalId: BigInteger!, $title: String!, $description: String!, $icon: String!) { " +
			"updateProposal(pollId: $pollId, proposalId: $proposalId, title: $title, description: $description, icon: $icon) " + JQL_POLL + "}";

	private Lson addVars(Long pollId, String title) {
		return Lson.builder().put("pollId", pollId).put("title", title)
				.put("description", "A description that is comfortably longer than twenty characters.")
				.put("icon", "atom");
	}

	private Lson updateVars(Long pollId, Long proposalId, String title, String description) {
		return Lson.builder().put("pollId", pollId).put("proposalId", proposalId)
				.put("title", title).put("description", description).put("icon", "hammer");
	}

	/** A fresh team plus one joined member. Returns {adminRes, memberRes}. */
	private TeamDataResponse[] freshTeamWithMember(String prefix) {
		TeamDataResponse admin = util.createFreshTeam(prefix);
		TeamDataResponse member = util.joinTeam(admin.team.getInviteCode(), null);
		return new TeamDataResponse[]{admin, member};
	}

	// ==================== who may ADD ====================

	@Test
	@DisplayName("membersCanAddProposals=true: a member may add SEVERAL proposals")
	public void membersMayAddManyWhenAllowed() {
		TeamDataResponse[] t = freshTeamWithMember("AddAllowed");
		TeamDataResponse admin = t[0], member = t[1];

		PollEntity poll = util.createPoll("Open brainstorming poll", admin.jwt, true);

		util.addProposal(poll.getId(), "Member's first idea", "First idea, described at length.", "atom", member.jwt);
		PollEntity after = util.addProposal(poll.getId(), "Member's second idea", "Second idea, described at length.", "atom", member.jwt);

		// This is the point of the change: no more one-proposal-per-member cap.
		assertEquals(2, after.getProposals().size(),
				"A member must be able to add more than one proposal when the poll allows it");
	}

	@Test
	@DisplayName("membersCanAddProposals=false: members are refused, the admin is not")
	public void onlyAdminMayAddWhenNotAllowed() {
		TeamDataResponse[] t = freshTeamWithMember("AddRefused");
		TeamDataResponse admin = t[0], member = t[1];

		PollEntity poll = util.createPoll("Admin sets the options", admin.jwt, false);

		assertRejected(ADD_PROPOSAL_QUERY, addVars(poll.getId(), "Member tries anyway"), member.jwt,
				"CANNOT_ADD_PROPOSAL", "a member must not add proposals to a poll that does not allow it");

		PollEntity after = util.addProposal(poll.getId(), "Admin's option A", "The admin may always add.", "atom", admin.jwt);
		assertEquals(1, after.getProposals().size(), "the admin may always add proposals");
	}

	@Test
	@DisplayName("createPoll without the argument defaults to admin-only")
	public void defaultIsAdminOnly() {
		TeamDataResponse[] t = freshTeamWithMember("AddDefault");
		TeamDataResponse admin = t[0], member = t[1];

		PollEntity poll = util.createPoll("Poll created without the flag", admin.jwt, null);

		assertFalse(poll.isMembersCanAddProposals(), "omitting membersCanAddProposals must mean OFF");
		assertRejected(ADD_PROPOSAL_QUERY, addVars(poll.getId(), "Member tries on a default poll"), member.jwt,
				"CANNOT_ADD_PROPOSAL", "the default must be closed, so a member cannot add");
	}

	// ==================== who may EDIT ====================

	@Test
	@DisplayName("The author may edit their own proposal while the poll has not started")
	public void authorMayEditOwnProposal() {
		TeamDataResponse[] t = freshTeamWithMember("EditOwn");
		TeamDataResponse admin = t[0], member = t[1];

		PollEntity poll = util.createPoll("Poll to edit in", admin.jwt, true);
		PollEntity withProposal = util.addProposal(poll.getId(), "Original title", "The original description, long enough.", "atom", member.jwt);
		Long proposalId = withProposal.getProposals().iterator().next().getId();

		TestFixtures.sendGraphQL(UPDATE_PROPOSAL_QUERY,
				updateVars(poll.getId(), proposalId, "Corrected title", "The corrected description, also long enough."),
				member.jwt);

		PollEntity reloaded = util.getPoll(poll.getId(), member.jwt);
		ProposalEntity edited = reloaded.getProposals().iterator().next();
		assertEquals("Corrected title", edited.getTitle());
		assertEquals("The corrected description, also long enough.", edited.getDescription());
		assertEquals("hammer", edited.getIcon(), "the icon must be editable too");
	}

	@Test
	@DisplayName("Somebody else's proposal may NOT be edited - not even by the admin")
	public void nonAuthorMayNotEdit() {
		TeamDataResponse[] t = freshTeamWithMember("EditForeign");
		TeamDataResponse admin = t[0], member = t[1];

		PollEntity poll = util.createPoll("Poll with a member's proposal", admin.jwt, true);
		PollEntity withProposal = util.addProposal(poll.getId(), "The member's words", "Written by the member, at length.", "atom", member.jwt);
		Long proposalId = withProposal.getProposals().iterator().next().getId();

		assertRejected(UPDATE_PROPOSAL_QUERY,
				updateVars(poll.getId(), proposalId, "Rewritten by the admin", "The admin should not be able to do this."),
				admin.jwt,
				"CANNOT_EDIT_PROPOSAL", "not even the admin may rewrite another person's proposal");
	}

	@Test
	@DisplayName("Once voting has started, nobody may edit")
	public void cannotEditAfterVotingStarted() {
		TeamDataResponse[] t = freshTeamWithMember("EditAfterStart");
		TeamDataResponse admin = t[0], member = t[1];

		PollEntity poll = util.createPoll("Poll that will start", admin.jwt, true);
		util.addProposal(poll.getId(), "Proposal one", "The first proposal, long enough.", "atom", member.jwt);
		PollEntity twoProposals = util.addProposal(poll.getId(), "Proposal two", "The second proposal, long enough.", "atom", member.jwt);
		Long proposalId = twoProposals.getProposals().stream()
				.filter(p -> "Proposal one".equals(p.getTitle())).findFirst().orElseThrow().getId();

		util.startVotingPhase(poll.getId(), admin.jwt);

		assertRejected(UPDATE_PROPOSAL_QUERY,
				updateVars(poll.getId(), proposalId, "Too late", "The ballot is frozen once voting starts."),
				member.jwt,
				"CANNOT_EDIT_PROPOSAL", "the ballot must be frozen once voting has started");
	}

	@Test
	@DisplayName("Renaming onto another proposal's title is refused")
	public void cannotRenameOntoAnotherTitle() {
		TeamDataResponse[] t = freshTeamWithMember("EditDuplicate");
		TeamDataResponse admin = t[0], member = t[1];

		PollEntity poll = util.createPoll("Poll with two proposals", admin.jwt, true);
		util.addProposal(poll.getId(), "Taken title", "Belongs to the first proposal.", "atom", member.jwt);
		PollEntity both = util.addProposal(poll.getId(), "My own title", "Belongs to the second proposal.", "atom", member.jwt);
		Long mine = both.getProposals().stream()
				.filter(p -> "My own title".equals(p.getTitle())).findFirst().orElseThrow().getId();

		assertRejected(UPDATE_PROPOSAL_QUERY,
				updateVars(poll.getId(), mine, "Taken title", "Trying to collide with the other proposal."),
				member.jwt,
				"CANNOT_EDIT_PROPOSAL", "two proposals in one poll must not share a title");
	}

	@Test
	@DisplayName("Saving a proposal unchanged succeeds - it must not collide with itself")
	public void savingUnchangedSucceeds() {
		TeamDataResponse[] t = freshTeamWithMember("EditUnchanged");
		TeamDataResponse admin = t[0], member = t[1];

		PollEntity poll = util.createPoll("Poll for a no-op save", admin.jwt, true);
		PollEntity withProposal = util.addProposal(poll.getId(), "Unchanged title", "An unchanged description, long enough.", "atom", member.jwt);
		Long proposalId = withProposal.getProposals().iterator().next().getId();

		// The uniqueness check must exclude the proposal being edited, or this collides with itself.
		TestFixtures.sendGraphQL(UPDATE_PROPOSAL_QUERY,
				updateVars(poll.getId(), proposalId, "Unchanged title", "A freshly edited description, long enough."),
				member.jwt);

		PollEntity reloaded = util.getPoll(poll.getId(), member.jwt);
		assertEquals("A freshly edited description, long enough.",
				reloaded.getProposals().iterator().next().getDescription(),
				"re-saving with the same title must be allowed");
	}
}
