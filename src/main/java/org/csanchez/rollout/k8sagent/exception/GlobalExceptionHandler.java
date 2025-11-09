package org.csanchez.rollout.k8sagent.exception;

import org.csanchez.rollout.k8sagent.model.KubernetesAgentResponse;

import io.quarkus.logging.Log;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Global exception handler for REST API endpoints.
 * Catches unhandled exceptions and returns structured error responses.
 */
@Provider
public class GlobalExceptionHandler implements ExceptionMapper<Exception> {
	@Override
	public Response toResponse(Exception e) {
		// Handle 404 errors separately with less alarming logging
		if (e instanceof NotFoundException) {
			Log.debug("404 Not Found - likely a request to a non-existent endpoint: " + e.getMessage());
			
			// Return 404 without custom response body for standard REST behavior
			return Response.status(Status.NOT_FOUND)
				.entity("{\"error\": \"Endpoint not found\"}")
				.build();
		}
		
		// Log other exceptions as errors
		Log.error("Unhandled exception in controller: " + e.getClass().getName() + " - " + e.getMessage(), e);
		
		KubernetesAgentResponse errorResponse = KubernetesAgentResponse.empty()
			.withAnalysis("Unhandled error: " + e.getMessage())
			.withRootCause("System error: " + e.getClass().getSimpleName())
			.withRemediation("Unable to provide remediation due to system error")
			.withPromote(true) // Default to promote on error
			.withConfidence(0);
		
		return Response.status(Status.INTERNAL_SERVER_ERROR)
			.entity(errorResponse)
			.build();
	}
}