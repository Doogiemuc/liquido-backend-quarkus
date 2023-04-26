package org.liquido.team;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
//import io.smallrye.common.constraint.NotNull;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * This Team entity is the data model of a team in the backend database.
 * See TeamGraphQL for the representation of a Team in the GraphQL API.
 */
@Data
@NoArgsConstructor             									// Lombok's Data does NOT include a default no args constructor!
@EqualsAndHashCode(of={}, callSuper = true)    	// Compare teams by their Id only. teamName may change.
@Entity
public class TeamEntity extends PanacheEntity {

	//ID field is already defined in PanacheEntity

  /** Name of team. TeamName must be unique over all teams! */
  @NotNull   // javax.validation.constraints.NotNull;
	@lombok.NonNull
	public String teamName;

  /** A code that can be sent to other users to invite them into your team. */
  public String inviteCode = null;

	// Each team has members and admins.
	// Each user may also be a member (or admin) of other teams.
	// So this is a @ManyToMany relationship
	// But each user may only be member (or admin) once in this team!

  /**
	 * Members and Admins of this team. Each team must have at least one admin.
   */
	@OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
  public Set<TeamMember> members = new HashSet<>();

	/** The polls in this team */
	//This is the one side of a bidirectional OneToMany relationship. Keep in mind that you then MUST add mappedBy to map the reverse direction.
	//And don't forget the @JsonBackReference on the many-side of the relation (in PollModel) to prevent StackOverflowException when serializing a TeamModel
	//OneToMany(mappedBy = "team", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)  // when a team is loaded, then do not immediately also load all polls
	//@JsonManagedReference
	//TODO: Set<PollModel> polls = new HashSet<>();   //BUGFIX: Changed from List to Set https://stackoverflow.com/questions/4334970/hibernate-throws-multiplebagfetchexception-cannot-simultaneously-fetch-multipl

	//TODO: move these to a base class
	@CreationTimestamp
	public LocalDateTime createdAt;

	@UpdateTimestamp
	public LocalDateTime updatedAt;

	/** Create a new Team entity */
	public TeamEntity(String teamName, UserEntity admin) {
		this.teamName = teamName;
		this.members.add(new TeamMember(this, admin, TeamMember.Role.ADMIN));
		this.inviteCode = DoogiesUtil.easyToken(8);
	}

	/*
	public boolean isAdmin(UserEntity admin) {
		return this.admins.contains(admin);
	}

	public boolean emailIsAdmin(String email) {
		return this.admins.stream().anyMatch(admin -> admin.email != null && admin.email.equals(email));
	}
	public boolean mobilephoneIsAdmin(String mobilephone) {
		return this.admins.stream().anyMatch(admin -> admin.mobilephone != null && admin.mobilephone.equals(mobilephone));
	}

	public boolean isMember(UserEntity member) {
		return this.members.contains(member);
	}
	public boolean emailIsMember(String email) {
		return this.members.stream().anyMatch(member -> member.email != null && member.email.equals(email));
	}
	public boolean mobilephoneIsMember(String mobilephone) {
		return this.members.stream().anyMatch(member -> member.mobilephone != null && member.mobilephone.equals(mobilephone));
	}

	 */

	/**
	 * Check if a user with that email is a member or admin of this team.
	 * @param email email of a user or admin in this team
	 * @param role filter by TeamMember.Role (optional, may be null)
	 * @return the user or admin if it is part of this team
  */
	public Optional<UserEntity> getMemberByEmail(String email, TeamMember.Role role) {
		if (email == null) return Optional.empty();
		return this.<TeamMember>getMembers().stream()
				.filter(tm -> role == null || tm.role.equals(role))
				.map(tm -> tm.getUser())
				.filter(u -> email.equals(u.email) )
				.findFirst();
	}


	public UserEntity getFirstAdmin() {
		if (this.members == null) throw new RuntimeException("team has no member HashSet. Should have been initialized.");
		Optional<TeamMember> member = this.members.stream().filter(teamMember -> teamMember.role == TeamMember.Role.ADMIN).findFirst();
		return member.orElseThrow().getUser();
	}



	// =================== Active Record - query methods ================

	/**
	 * Find one team by its name.
	 * @param teamName name  of team
	 * @return TeamEntity or Optional.empty() if not found.
	 */
	public static Optional<TeamEntity> findByTeamName(String teamName) {
		return find("teamName", teamName).firstResultOptional();
	}


  @Override
  public String toString() {
  	StringBuffer buf = new StringBuffer();
    buf.append("TeamModel[");
		buf.append("id=" + id);
		buf.append(", teamName='" + this.teamName + '\'');
		//buf.append(", firstAdmin='" + this.getFirstAdmin().orElse(null) + "'");
		//buf.append(", numAdminAndMembers=" + (this.admins.size() + this.members.size()));
		buf.append(']');
		return buf.toString();
  }



}
