package org.csanchez.adk.agents.k8sagent;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Web configuration to ensure REST controllers take precedence over static resources.
 * This is needed because the ADK web server registers a catch-all /** resource handler
 * that would otherwise intercept our /a2a/** REST endpoints.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebConfig {

	/**
	 * Configure request mapping handler to have highest priority.
	 * This ensures REST controllers are checked before static resource handlers.
	 */
	@Bean
	public WebMvcRegistrations webMvcRegistrations() {
		return new WebMvcRegistrations() {
			@Override
			public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
				RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
				// Set order to highest precedence so REST controllers are matched first
				mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
				return mapping;
			}
		};
	}
}
