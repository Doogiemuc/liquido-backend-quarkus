package org.liquido.model;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.spi.CDI;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;
import org.liquido.security.JwtTokenUtils;
import org.liquido.user.UserEntity;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Generator for the currently logged in user.
 * Uses for setting createdAt in {@link BaseEntity}
 *
 * by Vlad:  https://vladmihalcea.com/how-to-emulate-createdby-and-lastmodifiedby-from-spring-data-using-the-generatortype-hibernate-annotation/
 */
@Slf4j
@RequestScoped
public class LoggedInUserGenerator implements BeforeExecutionGenerator {

	@Override
	public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue, EventType eventType) {
		// Cannot simply @Inject JwtTokenUtils   must there is a cool workaround
		//https://stackoverflow.com/questions/61154494/dependency-injection-does-not-work-in-restclientbuilderlistener
		JwtTokenUtils jwtTokenUtils = CDI.current().select(JwtTokenUtils.class).get();
		Optional<UserEntity> userOpt = jwtTokenUtils.getCurrentUser();
		return userOpt.orElse(null);
	}

	@Override
	public EnumSet<EventType> getEventTypes() {
		return EventTypeSets.INSERT_ONLY;
	}
}