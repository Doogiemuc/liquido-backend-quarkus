package org.liquido.team;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.liquido.user.UserEntity;

import javax.persistence.Entity;
import javax.persistence.OneToOne;
import java.time.LocalDateTime;

/**
 * Link between a Team and its members and admins.
 */
@Data
@NoArgsConstructor                              // Lombok's Data does NOT include a default no args constructor!
@RequiredArgsConstructor
@EqualsAndHashCode(of={}, callSuper = true)    	// Compare teams by their Id only. teamName may change.
@Entity
public class TeamMember extends PanacheEntity {
	@OneToOne
	@lombok.NonNull
	TeamEntity team;

	@OneToOne
	@lombok.NonNull
	UserEntity user;

	@lombok.NonNull
	Role role;

	@CreationTimestamp
	LocalDateTime joinedAt;

	enum Role {
		MEMBER,
		ADMIN
	}
}
