package org.liquido;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import org.liquido.util.LiquidoConfig;

import javax.inject.Inject;

@Startup
//@ApplicationScoped
public class Liquido {

	/**
	 * This is called when app has started.
	 */
	Liquido() {
		LaunchMode launchMode = io.quarkus.runtime.LaunchMode.current();  // @Inject does not work.
		System.out.println("============== STARTING LIQUIDO in [" + launchMode + "]==================");

	}
}
