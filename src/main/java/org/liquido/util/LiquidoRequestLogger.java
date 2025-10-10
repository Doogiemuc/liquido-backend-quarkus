package org.liquido.util;

import com.google.common.base.Strings;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Log all HTTP requests and responses
 */
@Slf4j
public class LiquidoRequestLogger {

	public static boolean logHeaders = false;

	AtomicLong requestCounter = new AtomicLong(0);

	@RouteFilter(100)
	void myLogFilter(RoutingContext ctx) {

		long now = System.currentTimeMillis() % 100000;  // simple unique  request ID
		long count = this.requestCounter.incrementAndGet();
		if (count > 99999) {
			this.requestCounter.set(0);
			count = 0;
		}
		String countPadded = Strings.padStart(String.valueOf(count), 6, ' ');

		ctx.request().exceptionHandler(err -> log.error("=> ["+now+"] RequestException: " + err.getMessage()));
		ctx.response().exceptionHandler(err -> log.error("<= ["+now+"] ResponseException: " + err.getMessage()));

		// Log request
		String requestMsg = new StringBuilder()
				.append("=> [")
				.append(countPadded)
				//.append(".")
				//.append(now)
				.append("] ")
				.append(ctx.request().method())
				.append(" ")
				.append(ctx.request().absoluteURI()).toString();
		log.debug(requestMsg);
		if (logHeaders) ctx.request().headers().forEach((key, value) -> log.debug("  " + key + ": " + value));

		// Log request body and THEN the response
		ctx.request().bodyHandler(body -> {
			String bodyContent = body.toString(); 			// Convert the buffer to a string
			if (!bodyContent.isEmpty()) {
				if (bodyContent.length() > 500) bodyContent = bodyContent.substring(0, 500);
				log.debug("=> [{}]     {}", countPadded, bodyContent);
			}

			// Log response
			String responseMsg = new StringBuilder()
					.append("<= [")
					.append(countPadded)
					//.append(".")
					//.append(now)
					.append("] ")
					.append(ctx.response().getStatusCode())
					.append(" ")
					.append(ctx.response().getStatusMessage()).toString();
			log.debug(responseMsg);
			if (logHeaders) ctx.response().headers().forEach((key, value) -> log.debug("  " + key + ": " + value));
		});



		//TODO: Log response body (which is tricky! Need to wrap the response to capture the body)




		ctx.next();  // important!
	}
}