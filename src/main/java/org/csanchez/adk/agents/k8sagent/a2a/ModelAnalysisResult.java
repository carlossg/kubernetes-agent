package org.csanchez.adk.agents.k8sagent.a2a;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the analysis result from a single LLM model.
 * Used for multi-model parallel analysis and voting.
 */
public class ModelAnalysisResult {
	
	@JsonProperty("modelName")
	private String modelName;
	
	@JsonProperty("analysis")
	private String analysis;
	
	@JsonProperty("rootCause")
	private String rootCause;
	
	@JsonProperty("remediation")
	private String remediation;
	
	@JsonProperty("promote")
	private boolean promote;
	
	@JsonProperty("confidence")
	private int confidence;
	
	@JsonProperty("executionTimeMs")
	private long executionTimeMs;
	
	@JsonProperty("error")
	private String error; // If analysis failed
	
	public ModelAnalysisResult() {
	}
	
	public ModelAnalysisResult(String modelName, String analysis, String rootCause, 
	                          String remediation, boolean promote, int confidence, 
	                          long executionTimeMs) {
		this.modelName = modelName;
		this.analysis = analysis;
		this.rootCause = rootCause;
		this.remediation = remediation;
		this.promote = promote;
		this.confidence = confidence;
		this.executionTimeMs = executionTimeMs;
	}
	
	// Getters and setters
	public String getModelName() {
		return modelName;
	}
	
	public void setModelName(String modelName) {
		this.modelName = modelName;
	}
	
	public String getAnalysis() {
		return analysis;
	}
	
	public void setAnalysis(String analysis) {
		this.analysis = analysis;
	}
	
	public String getRootCause() {
		return rootCause;
	}
	
	public void setRootCause(String rootCause) {
		this.rootCause = rootCause;
	}
	
	public String getRemediation() {
		return remediation;
	}
	
	public void setRemediation(String remediation) {
		this.remediation = remediation;
	}
	
	public boolean isPromote() {
		return promote;
	}
	
	public void setPromote(boolean promote) {
		this.promote = promote;
	}
	
	public int getConfidence() {
		return confidence;
	}
	
	public void setConfidence(int confidence) {
		this.confidence = confidence;
	}
	
	public long getExecutionTimeMs() {
		return executionTimeMs;
	}
	
	public void setExecutionTimeMs(long executionTimeMs) {
		this.executionTimeMs = executionTimeMs;
	}
	
	public String getError() {
		return error;
	}
	
	public void setError(String error) {
		this.error = error;
	}
	
	@Override
	public String toString() {
		return "ModelAnalysisResult{" +
				"modelName='" + modelName + '\'' +
				", promote=" + promote +
				", confidence=" + confidence +
				", executionTimeMs=" + executionTimeMs +
				", error='" + error + '\'' +
				'}';
	}
}
