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

@EqualsAndHashCode(callSuper = false)  //TODO: test equals and hashcode on my LiquidoBaseEntitry.  do USers and polls equal correctly?
@MappedSuperclass
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

	public Long getId() {
		return this.id;
	}
}