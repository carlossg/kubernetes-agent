package org.csanchez.adk.agents.k8sagent.a2a;

/**
 * Tracks timing metrics for model and tool execution
 */
public class ExecutionMetrics {
	private long totalModelTimeMs = 0;
	private long totalToolTimeMs = 0;
	private int toolCallCount = 0;
	private int modelCallCount = 0;
	
	public void addModelTime(long timeMs) {
		this.totalModelTimeMs += timeMs;
		this.modelCallCount++;
	}
	
	public void addToolTime(long timeMs) {
		this.totalToolTimeMs += timeMs;
		this.toolCallCount++;
	}
	
	public long getTotalModelTimeMs() {
		return totalModelTimeMs;
	}
	
	public long getTotalToolTimeMs() {
		return totalToolTimeMs;
	}
	
	public int getToolCallCount() {
		return toolCallCount;
	}
	
	public int getModelCallCount() {
		return modelCallCount;
	}
	
	public long getTotalTimeMs() {
		return totalModelTimeMs + totalToolTimeMs;
	}
}
