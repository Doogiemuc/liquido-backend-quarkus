package org.liquido;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.HttpConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;

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

	@ConfigProperty(name = "quarkus.datasource.username")
	String datasourceUsername;

	@ConfigProperty(name = "quarkus.datasource.jdbc.url")
	String jdbcUrl;

	@ConfigProperty(name = "quarkus.hibernate-orm.database.generation")
	String databaseGeneration;

	@ConfigProperty(name = "quarkus.profile")
	String quarkusProfile;

	@Inject
	HttpConfiguration httpConfig;

	/**
	 * This is called when app has started.
	 * Here we output as much debugging data as possible.
	 * And we also sanity check the connection to and content of our DB.
	 */
	void onStart(@Observes StartupEvent ev) {
		LaunchMode launchMode = io.quarkus.runtime.LaunchMode.current();
		System.out.println("============== STARTING LIQUIDO in [" + launchMode + "]==================");
		System.out.println("   QUARKUS_PROFILE : " + quarkusProfile);
		System.out.println("   DB generation   : " + hibernateDbGeneration);
		System.out.println("   Frontend URL    : " + config.frontendUrl());
		//System.out.println("   Backend        : http://"+httpConfig.host+":"+httpConfig.port);
		System.out.println("   Backend (SSL)   : https://"+httpConfig.host+":"+httpConfig.sslPort);
		System.out.println("=============== DB INFO ===============");
		System.out.println("   DB Username     : " + datasourceUsername);
		System.out.println("   DB JDBC URL     : " + jdbcUrl);
		System.out.println("   DB Generation   : " + databaseGeneration);


		try {
			System.out.println("   DB Connection   : " + dataSource.getConnection().getMetaData().getURL());
		} catch (SQLException e) {
			log.error("=====================================");
			log.error("Cannot connect to DB!" + e.getMessage());
			log.error("=====================================");
			throw new RuntimeException(e);
		}

		try {
			System.out.println("=========== Table counts ===========");
			System.out.println("   #Users          : " + UserEntity.count());
			System.out.println("   #Teams          : " + TeamEntity.count());
			System.out.println("   #Polls          : " + PollEntity.count());
		} catch (Exception e) {
			log.error("==================================================");
			log.error(" Users, Teams or Polls table does not exist.");
			log.error(" Is your database initialized with test data?");
			log.error("==================================================");
			throw e;
		}
		System.out.println("=====================================");

		// Uncomment this to fill db with sample data
		//PreparedStatement ps = dataSource.getConnection().prepareStatement("SCRIPT TO '" + sampleDbFile + "'");


	}


}