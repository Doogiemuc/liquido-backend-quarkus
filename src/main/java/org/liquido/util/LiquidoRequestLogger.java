package org.liquido.util;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Log all HTTP requests.
 */
@Slf4j
public class LiquidoRequestLogger {

	@RouteFilter(100)
	void myLogFilter(RoutingContext ctx) {
		String msg = "=> " +
				ctx.request().method() + " " +
				ctx.request().absoluteURI();
		log.info(msg);
		ctx.next();  // important!
	}
}