package org.liquido.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.liquido.user.UserEntity;

import java.time.LocalDateTime;

/**
 * Base class for all LIQUIDO database entities.
 * Each entity by default has a createdAt, updatedAt and createdBy that is automatically filled on save.
 * See {@link LoggedInUserGenerator}
 *
 * Do not forget to add a @NoArgsConstructor to every parent class
 *
 * <h3>Equality</h3>
 * By default, Liquido Entities are considered to be equal if and only if their ID is set and the same.
 * Two not yet persisted entities are *not* equal, even if they share the same data.
 * Parent classes should adapt and extend this. For example two UserEntities are only equal if their IDs
 * <b>and</b> email addresses match.
 *
 */
//@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)  // This also works. In the same way as the code below. But I don't want to rely on super.equals()
@MappedSuperclass  // This JPA class does not have a DB table itself. Only its "mapped" superclasses have.
public class BaseEntity extends PanacheEntity {

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	public LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false, updatable = false)
	public LocalDateTime updatedAt;

	@ManyToOne
	@CreatedBy
	public UserEntity createdBy;

	@EqualsAndHashCode.Include
	public Long getId() {
		return this.id;
	}

	/*
	 * Two BaseEntities are equal when their ID field is not null and has the same value
	 * @param obj a BaseEntity instance
	 * @return true if IDs are not null and equal
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		BaseEntity other = (BaseEntity) obj;
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return id != null ? id.hashCode() : 0;
	}
}