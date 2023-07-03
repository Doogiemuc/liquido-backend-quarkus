package org.liquido.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Ignore;
import org.liquido.util.DoogiesUtil;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * One user / voter / citizen / member of a team
 * When a user creates a new team, then he becomes the admin of that team.
 * A user may also join other teams. Then he is a member in those teams.
 */
@Data
@EqualsAndHashCode(callSuper = true)  			// compare Users by their ID (names may change)
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@Entity(name = "liquido_user")
//DEPRECATED: @GraphQLType(name="user", description = "A LiquidoUser that can be an admin or member in a team.")  // well be named "userInput" by graphql-spqr
//We now have separate types for GraphQL. The type in the exposed API might be different from this ORM Panache entity!
public class UserEntity extends PanacheEntity {
	/*
	About the equality of UserModels

	Equality is a big thing in Java! :-) And it is an especially nasty issue for UserModels.
	When are two user models "equal" to each other?
	 - when their ID matches?
	 - when they have the same email address? (this is what most people would assume at first.) In the same case?
	 - when they have the same mobile phone? (security relevant for login via SMS token!) With or without some spaces and pluses and nulls in between?
	 - do the other attributes also need to match (deep equals)
	Always distinguish between a "user" and a "human being"! A human might register multiple times with independent email addresses!
	There is no way an app could ever prevent this (other than requiring DNA tests).

	LIQUIDO UserModels equal when their ID matches. This should normally always imply that all the other attributes also match.
	If not, we've been hacked!

	Implementation note:
	A UserModel does not contain a reference to a team. Only the TeamModel has members and admins. (Helps a lot with preventing JsonBackReferences)
	One user may be admin or member of several teams!
	*/

	/** Username, Nickname */
	@NotNull
	@lombok.NonNull
	public String name;

	/**
	 * User's email address. This email must be unique within the team.
	 * Emails are always stored in lowercase in the DB!
	 * A user may be registered with the same email in <em>different</em> teams.
	 */
	@NotNull
	@lombok.NonNull
	@Column(unique = true)
  public String email;

	/**
	 * User's mobile phone number. Needed for login via SMS code.
	 * Mobilephone numbers in the DB are cleaned first: See cleanMobilePhone()
	 */
	@NotNull
	@lombok.NonNull
	//@Column(unique = true)  //MAYBE: Are you really sure that every user have their own mobile phone? Or do some people share their mobilephone? Think worldwide!
	public String mobilephone;

	/** (optional) User's website or bio or social media profile link */
	public String website = null;

	/** Avatar picture URL */
	public String picture = null;

	/**
	 * Time based one time password (TOTP) URI
	 * This URI contains a secret!
	 * Used for generating a QR code that can then be scanned with the Authy App.
	 */
	@Ignore  // ignore in GraphQL
	@JsonIgnore
	public String totpFactorUri;

	/**
	 * SID of this user's authy Factor ("YF......")
	 */
	@Ignore  // ignore in GraphQL and JSON
	@JsonIgnore
	public String totpFactorSid;

	/** Last team the user was logged in. This is used when user is member of multiple teams. */
	@DefaultValue(value = "-1")  // for graphQL
	public long lastTeamId = -1;  // MUST init, so that GraphQL will not put this field into UserModelInput

	/** timestamp of last login */
	LocalDateTime lastLogin = null;

	// ====================== Getters and setters (only where logic is necessary)  ===================

	/** Email will always be stored in lowercase. This will be handled by quarkus-panache. */
	public void setEmail(String email) {
		if (email == null || email.trim().length() == 0) throw new RuntimeException("A user's email must not be null!");
		this.email = email.toLowerCase();
	}

	// ====================== Active Record - query methods ===================

	/**
	 * Find a user by email
	 * @param email will be converted to lowercase
	 * @return the found user or Optional.empty()
	 */
	public static Optional<UserEntity> findByEmail(String email) {
		if (email == null) return Optional.empty();
		return UserEntity.find("email", email.toLowerCase()).firstResultOptional();
	}

	public static Optional<UserEntity> findByMobilephone(String mobilephone) {
		mobilephone = DoogiesUtil.cleanMobilephone(mobilephone);
		return UserEntity.find("mobilephone", mobilephone).firstResultOptional();
	}

	@Override
  public String toString() {
  	StringBuffer buf = new StringBuffer();
    buf.append("UserModel[");
		buf.append("id=" + id);
		buf.append(", email='" + email + '\'');
		buf.append(", name='" + name + '\'');
		buf.append(", mobilephone=" + mobilephone);
		buf.append(", picture=" + picture);
		buf.append(']');
		return buf.toString();
  }

  public String toStringShort() {
		StringBuffer buf = new StringBuffer();
		buf.append("UserModel[");
		buf.append("id=" + id);
		buf.append(", email='" + email + '\'');
		buf.append(']');
		return buf.toString();
	}

}