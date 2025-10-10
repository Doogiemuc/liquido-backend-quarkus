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
    long now = System.currentTimeMillis() % 100000;
    long count = this.requestCounter.incrementAndGet();
    if (count > 99999) {
        this.requestCounter.set(0);
        count = 0;
    }
    String countPadded = Strings.padStart(String.valueOf(count), 6, ' ');

    ctx.request().exceptionHandler(err -> log.error("=> [" + now + "] RequestException: " + err.getMessage()));
    ctx.response().exceptionHandler(err -> log.error("<= [" + now + "] ResponseException: " + err.getMessage()));

    // ========== Log Request
    log.debug("=> [{}] {} {}", countPadded, ctx.request().method(), ctx.request().absoluteURI());
    if (logHeaders) ctx.request().headers().forEach((key, value) -> log.debug("  {}: {}", key, value));

		// ========= Log Response
		log.debug("<= [{}] {}", countPadded, ctx.response().getStatusCode());

		/*  All this does not work :-(

		SimpleInstrumentationContext.whenCompleted((object, throwable) -> {
			log.info("whenCompleted SSSSSSSS");
			log.info(object.toString());
			log.info(throwable.toString());
		});

		// Wrap response
    HttpServerResponse originalResponse = ctx.response();
    LiquidoLoggingResponseWrapper responseWrapper = new LiquidoLoggingResponseWrapper(originalResponse);

		ctx.addBodyEndHandler(v -> {
			log.debug("Body End handler SSSSSSSS");
		});

    responseWrapper.bodyEndHandler(v -> {
        // Log the captured response body and status code when the response finishes
        log.debug("<=[Wrapper]= [{}] {} {}", countPadded, responseWrapper.getStatusCode(), responseWrapper.getResponseBody());
    });


		 */

    ctx.next(); // Move to the next handler
	}
}