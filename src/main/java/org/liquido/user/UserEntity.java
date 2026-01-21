package org.liquido.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Ignore;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.liquido.security.PasswordServiceBcrypt;
import org.liquido.security.webauthn.WebAuthnCredential;
import org.liquido.util.DoogiesUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <h1>A LIQUIDO voter</h1>
 *
 * <p>A user in LIQUIDO is a voter that can cast a vote in a poll. Voters may only cast exactly one vote in a poll.
 * So LIQUIDO voters are different from normal "user accounts" in other applications. A user account is identified by an email address.
 * But one "human being" could easily have several emails and thus register for several user accounts.
 * In a voting application this must be prevented. A voter, one human being, must uniquely be identified in LIQUIDO.
 * Therefor LIQUIDO enforces the following registration process</p>
 *
 * <ol>
 *   <li>A new voter registers either by creating a new team or by joining an existing team with an invitation code.</li>
 *   <li>The new user initially registers with his email address and chooses a password. But this registration must then still be verified.</li>
 *   <li>The registration must be verified with a biometric authenticator, such as Fingerprint or FaceID.</li>
 * </ol>
 *
 * <p>A user may create or join more than one team. Then he can switch between these teams.</p>
 */
@Data
@NoArgsConstructor
//@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)    //BUGFIX: We have our own equals() implementation.
@Entity(name = "liquido_user")
//DEPRECATED: @GraphQLType(name="user", description = "A LiquidoUser that can be an admin or member in a team.")  // Don't need to manually name the GraphQL DTO. It will be named "userInput" by graphql-spqr
//BUGFIX: UserEntity does not extend LiquidoBaseEntity. Yes we want createdAt and updatedAt. But we cant have a createdBy, because this would lead to a circular dependency.
public class UserEntity extends PanacheEntity {
	//TODO: Rename to VoterEntity
	//TODO: Do I need a __separate__ type for the upstream GraphQL API? The fields in the exposed API are different from this ORM Panache entity!
	/*
	#### Lombok @Data
	From the lombok docu: https://projectlombok.org/features/Data
	The @Data annotation creates getters for all fields, setters for all non-final fields, and appropriate toString, equals and hashCode implementations
	that involve the fields of the class, and a constructor that initializes all final fields, as well as all non-final fields with no initializer
	that have been marked with @NonNull, in order to ensure the field is never null.
	=> But be careful, it does NOT create a no-args constructor! And also no getters for fields of the parent class (see getId)!


	##### About the equality of UserEntities

	Equality is a big thing in Java! :-) And it is an especially nasty issue for UserModels.
	When are two user models "equal" to each other?
	 - when their ID matches?
	 - when they have the same email address? (this is what most people would assume at first.) In the same case?
	 - when they have the same mobile phone? (security relevant for login via SMS token!) With or without some spaces and pluses and nulls in between?
	 - do the other attributes also need to match (deep equals)
	Always distinguish between a "user" and a "human being"! A human might register multiple times with independent email addresses!
	There is no way an app could ever prevent this (other than requiring DNA tests).

	LIQUIDO UserModels equal when their ID and email matches. This should normally always imply that all the other attributes also match.
	If not, we've been hacked!

	Implementation note:
	A UserModel does not contain a reference to a team. Only the TeamModel has members and admins. (Helps a lot with preventing JsonBackReferences)
	One user may be admin or member of several teams!
	*/

	//@EqualsAndHashCode.Include
	public Long id;  // inherited from PanacheEntity

	// UserEntity has a createdAt and and updatedAt but no "createdBy", because this would lead to a circular dependency.
	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	public LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false, updatable = false)
	public LocalDateTime updatedAt;

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
	//@EqualsAndHashCode.Include
  public String email;

	/**
	 * User's mobile phone number. Needed for login via SMS code.
	 * Mobilephone numbers in the DB are cleaned first: See cleanMobilePhone()
	 */
	//@NotNull
	//@lombok.NonNull
	//@Column(unique = true)  //MAYBE: Are you really sure that every user have their own mobile phone? Or do some people share their mobilephone? Think worldwide!
	public String mobilephone;

	/** User's hashed password */
	@JsonIgnore
	public String passwordHash = null;

	/** (optional) User's website or bio or social media profile link */
	public String website = null;

	/** Avatar picture URL */
	public String picture = null;

	/** Last team the user was logged in. This is used when user is member of multiple teams. */
	@DefaultValue(value = "-1")  // for graphQL
	public long lastTeamId = -1;  // MUST init, so that GraphQL will not put this field into UserModelInput

	/** timestamp of last login */
	LocalDateTime lastLogin = null;   // this is set in doLoginInternal()

	/**
	 * Create a new user with this password. The password will of course only be stored in a hashed form.
	 */
	public UserEntity(@NonNull String name, @NonNull String email, @NonNull String plainPassword, String mobilephone, String website, String picture) {
		this.name = name;
		this.email = email;
		this.passwordHash = PasswordServiceBcrypt.hashPassword(plainPassword);
		this.mobilephone = mobilephone;
		this.website = website;
		this.picture = picture;
	}

	public UserEntity(@NonNull String name, @NonNull String email, @NonNull String plainPassword) {
		this(name, email, plainPassword, null, null, null);
	}


	// ========= Security related fields (NOT exposed in GraphQL) =============

	/**
	 * Time based one time password (TOTP) URI
	 * This URI contains a secret!
	 * Used for generating a QR code that can then be scanned with the Authy App.
	 */
	@Ignore  //SECURITY IMPORTANT: ignore in GraphQL and JSON
	@JsonIgnore
	public String totpFactorUri;

	/**
	 * SID of this user's authy Factor ("YF......")
	 */
	@Ignore  //SECURITY IMPORTANT: ignore in GraphQL and JSON
	@JsonIgnore
	public String totpFactorSid;

	/**
	 * Passwordless authentication with FaceID, fingerprint or hardware token.
	 * One user may have more than one credential registered.
	 */
	@OneToMany(mappedBy = "liquidoUser", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	@Ignore  //SECURITY IMPORTANT: ignore in GraphQL and JSON
	@JsonIgnore
	public List<WebAuthnCredential> webAuthnCredentials = new ArrayList<>();


	// ====================== Getters and setters (only where logic is necessary)  ===================

	/** Email will always be stored in lowercase. This will be handled by quarkus-panache. */
	public void setEmail(String email) {
		if (email == null || email.trim().isEmpty()) throw new RuntimeException("A user's email must not be null!");
		this.email = email.toLowerCase();
	}

	// ====================== Active Record - query methods ===================

	/**
	 * Find a user by email
	 * @param email will be converted to lowercase
	 * @return the found user or Optional.empty()
	 */
	public static Optional<UserEntity> findByEmail(String email) {
		if (email == null || email.trim().isEmpty()) return Optional.empty();
		return UserEntity.find("email", email.toLowerCase()).firstResultOptional();
	}

	public static Optional<UserEntity> findByMobilephone(String mobilephone) {
		mobilephone = DoogiesUtil.cleanMobilephone(mobilephone);
		return UserEntity.find("mobilephone", mobilephone).firstResultOptional();
	}

	/**
	 * We assume that two persisted liquido voters entities are the same human being, if
	 * <ul>
	 *   <li>they are the same java object reference, or</li>
	 *   <li>they both have the same ID and email</li>
	 * </ul>
	 * Every other attribute, e.g. the mobile phone number might even be different.
	 * From the point of view of a secure voting application it's still the same human being!
	 *
	 * @param o the reference object with which to compare.
	 * @return true if both objects represent the same voter
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		UserEntity that = (UserEntity) o;
		return id != null && id.equals(that.id) && email.equals(that.email);
	}

	@Override
	public int hashCode() {
		int result = id.hashCode();
		result = 31 * result + email.hashCode();
		return result;
	}

	@Override
  public String toString() {
  	StringBuilder buf = new StringBuilder();
    buf.append("UserModel[");
		buf.append("id=").append(id);
		buf.append(", email='").append(email).append('\'');
		buf.append(", name='").append(name).append('\'');
		buf.append(", mobilephone=").append(mobilephone);
		buf.append(", picture=").append(picture);
		buf.append(']');
		return buf.toString();
  }

  public String toStringShort() {
		StringBuilder buf = new StringBuilder();
		buf.append("UserModel[");
		buf.append("id=").append(id);
		buf.append(", email='").append(email).append('\'');
		buf.append(']');
		return buf.toString();
	}
}