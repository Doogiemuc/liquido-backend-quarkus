package org.liquido.poll;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.hibernate.tuple.ValueGenerator;
import org.liquido.security.JwtTokenUtils;
import org.liquido.user.UserEntity;

import javax.enterprise.context.RequestScoped;
import javax.enterprise.inject.spi.CDI;
import java.util.Optional;

@Slf4j
@RequestScoped
public class LoggedInUserGenerator implements ValueGenerator<UserEntity> {

	//by Vlad:  https://vladmihalcea.com/how-to-emulate-createdby-and-lastmodifiedby-from-spring-data-using-the-generatortype-hibernate-annotation/


	@Override
	public UserEntity generateValue(Session session, Object owner) {
		// owner is the Entity that we are creating

		// Cannot simply @Inject
		//https://stackoverflow.com/questions/61154494/dependency-injection-does-not-work-in-restclientbuilderlistener
		JwtTokenUtils jwtTokenUtils = CDI.current().select(JwtTokenUtils.class).get();

		Optional<UserEntity> userOpt = jwtTokenUtils.getCurrentUser();
		return userOpt.orElse(null);
	}
}
