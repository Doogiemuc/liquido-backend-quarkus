package org.liquido.util;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Log all HTTP requests.
 */
@Slf4j
public class LiquidoRequestLogger {

	public static boolean logHeaders = false;

	@RouteFilter(100)
	void myLogFilter(RoutingContext ctx) {
		String msg = "=> " +
				ctx.request().method() + " " +
				ctx.request().absoluteURI();
		log.debug(msg);
		ctx.request().headers().forEach((key, value) -> log.debug("  " + key + ": " + value));

		msg = "<= " +
				ctx.response().getStatusCode() + " " +
				ctx.response().getStatusMessage();
		log.debug(msg);

		if (logHeaders)
			ctx.response().headers().forEach((key, value) -> log.debug("  " + key + ": " + value));

		ctx.next();  // important!
	}
}