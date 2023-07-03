package org.liquido;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.HttpConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.liquido.util.LiquidoConfig;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.sql.SQLException;

//@Startup
@Slf4j
@ApplicationScoped
public class Liquido {

	@Inject
	AgroalDataSource dataSource;

	@Inject
	LiquidoConfig config;

	@ConfigProperty(name = "quarkus.hibernate-orm.database.generation")
	String hibernateDbGeneration;

	@Inject
	HttpConfiguration httpConfig;

	/**
	 * This is called when app has started.
	 * Here we output some useful debugging data.
	 */
	void onStart(@Observes StartupEvent ev) {
		LaunchMode launchMode = io.quarkus.runtime.LaunchMode.current();
		System.out.println("============== STARTING LIQUIDO in [" + launchMode + "]==================");
		try {
			System.out.println("           DB Connection : " + dataSource.getConnection().getMetaData().getURL());
			System.out.println(" Hibernate DB generation : " + hibernateDbGeneration);
		} catch (SQLException e) {
			log.error("Cannot connect to DB!");
			throw new RuntimeException(e);
		}

		System.out.println(" Listening on :            http://"+httpConfig.host+":"+httpConfig.port);

		System.out.println("=====================================");

		//PreparedStatement ps = dataSource.getConnection().prepareStatement("SCRIPT TO '" + sampleDbFile + "'");
	}


}