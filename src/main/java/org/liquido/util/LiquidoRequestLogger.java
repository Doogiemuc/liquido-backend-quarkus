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

		long now = System.currentTimeMillis() % 10000;  // simply dummy request ID

		// Log request
		String requestMsg = "=> [" + now + "] " +
				ctx.request().method() + " " +
				ctx.request().absoluteURI();
		log.debug(requestMsg);
		if (logHeaders) ctx.request().headers().forEach((key, value) -> log.debug("  " + key + ": " + value));

		// Log request body
		ctx.request().bodyHandler(body -> {
			String bodyContent = body.toString(); 			// Convert the buffer to a string
			if (!bodyContent.isEmpty()) {
				if (bodyContent.length() > 500) bodyContent = bodyContent.substring(0, 500);
				log.debug("=> [{}] BODY:{}", now, bodyContent);
			}
		});

		// Log response
		String responseMsg = "<= [" + now + "] " +
				ctx.response().getStatusCode() + " " +
				ctx.response().getStatusMessage();
		log.debug(responseMsg);
		if (logHeaders) ctx.response().headers().forEach((key, value) -> log.debug("  " + key + ": " + value));

		//TODO: Log response body (which is tricky! Need to wrap the response to capture the body)


		ctx.response().exceptionHandler(err -> log.error("<= ["+now+"] Exception: " + err.getMessage(), err));

		ctx.next();  // important!
	}
}