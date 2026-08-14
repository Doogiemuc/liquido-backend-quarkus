package org.liquido.team;

import com.fasterxml.jackson.annotation.JsonBackReference;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.liquido.user.UserEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Link between a Team and its members and admins.
 */
@Data
@NoArgsConstructor(force = true)                              // Lombok's Data does NOT include a default no args constructor!
@RequiredArgsConstructor
@EqualsAndHashCode(of={}, callSuper = true)    	// Compare teams by their Id only. teamName may change.
@Entity(name = "team_members")
public class TeamMemberEntity extends PanacheEntity {
	@ManyToOne
	@lombok.NonNull
	@JsonBackReference
	TeamEntity team;

	/**
	 * The member. MUST be @ManyToOne, never @OneToOne.
	 *
	 * A user can be a member of many teams, so a user has many TeamMemberEntity rows - one per team.
	 * @OneToOne makes Hibernate generate a UNIQUE constraint on the user_id column, which silently caps
	 * every user in the whole system at exactly one team membership. Nothing fails when a user joins
	 * their first team; the constraint only bites when they try to join a second one, and it surfaces
	 * as an opaque "Cannot join team" 500 rather than anything pointing at the mapping.
	 *
	 * This is the same bug as DelegationEntity.toProxy - see the "cardinality is asymmetric" note in
	 * AGENTS.md. Note that findTeamsByMember() below returns a List<TeamEntity>: this class always
	 * intended one user to be in many teams, only the annotation disagreed.
	 */
	@ManyToOne
	@lombok.NonNull
	UserEntity user;

	@lombok.NonNull
	Role role;

	@CreationTimestamp
	LocalDateTime joinedAt;

	public enum Role {
		MEMBER,
		ADMIN
	}

	/**
	 * Find all teams that a user is member (or admin) of.
	 *
	 * <p>The sort is <b>not</b> cosmetic. Callers treat the first element as a fallback
	 * ("log the user into their first team when the last one is gone", see
	 * {@code JwtTokenUtils.doLoginInternal}), and without an ORDER BY that would be "the first row
	 * of an unordered scan", which Postgres is free to reorder whenever a row is UPDATEd - and every
	 * single login UPDATEs a user row. See the note about {@code getRandomTeam()} in AGENTS.md for
	 * what that class of bug looks like when it bites. It also keeps the team switcher's dropdown in
	 * a stable order between renders.
	 *
	 * @param user a user (team member or admin)
	 * @return List of teams that this user is a member of, ordered by team id
	 */
	public static List<TeamEntity> findTeamsByMember(UserEntity user) {
		return TeamMemberEntity.<TeamMemberEntity>find("user", Sort.by("team.id"), user)
				.stream().map(tm -> tm.getTeam()).toList();
	}

}