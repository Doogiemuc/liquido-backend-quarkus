package org.liquido;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import lombok.extern.slf4j.Slf4j;
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

	/**
	 * This is called when app has started.
	 */
	void onStart(@Observes StartupEvent ev) {
		LaunchMode launchMode = io.quarkus.runtime.LaunchMode.current();
		System.out.println("============== STARTING LIQUIDO in [" + launchMode + "]==================");
		try {
			System.out.println("    DB       : " + dataSource.getConnection().getMetaData().getURL());
		} catch (SQLException e) {
			log.error("Cannot connect to DB!");
			throw new RuntimeException(e);
		}
		System.out.println("=====================================");

		//PreparedStatement ps = dataSource.getConnection().prepareStatement("SCRIPT TO '" + sampleDbFile + "'");
	}


}