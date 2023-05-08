package org.liquido.poll;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenerationTime;
import org.hibernate.annotations.GeneratorType;
import org.hibernate.annotations.UpdateTimestamp;
import org.liquido.user.UserEntity;

import javax.persistence.Column;
import javax.persistence.ManyToOne;
import javax.persistence.MappedSuperclass;
import java.time.LocalDateTime;

@MappedSuperclass
public class BaseEntity extends PanacheEntity {

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	public LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(nullable = false, updatable = false)
	public LocalDateTime updatedAt;

	@ManyToOne
	@GeneratorType(
			type = LoggedInUserGenerator.class,
			when = GenerationTime.INSERT
	)
	public UserEntity createdBy;

}
