package org.liquido.team;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
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
	TeamEntity team;

	@OneToOne
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
	 * @param user a user (team member or admin)
	 * @return List of teams that this user is a member of
	 */
	public static List<TeamEntity> findTeamsByMember(UserEntity user) {
		return TeamMemberEntity.<TeamMemberEntity>find("user", user).stream().map(tm -> tm.getTeam()).toList();
	}
}