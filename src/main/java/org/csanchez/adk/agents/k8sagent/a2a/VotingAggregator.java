package org.csanchez.adk.agents.k8sagent.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Aggregates multiple model analysis results using confidence-weighted voting.
 */
public class VotingAggregator {
	
	private static final Logger logger = LoggerFactory.getLogger(VotingAggregator.class);
	
	/**
	 * Result of aggregating multiple model analyses
	 */
	public static class AggregatedResult {
		private final boolean promote;
		private final double promoteScore;
		private final double rollbackScore;
		private final String votingRationale;
		private final String consolidatedAnalysis;
		private final String consolidatedRootCause;
		private final String consolidatedRemediation;
		private final int averageConfidence;
		
		public AggregatedResult(boolean promote, double promoteScore, double rollbackScore,
		                       String votingRationale, String consolidatedAnalysis,
		                       String consolidatedRootCause, String consolidatedRemediation,
		                       int averageConfidence) {
			this.promote = promote;
			this.promoteScore = promoteScore;
			this.rollbackScore = rollbackScore;
			this.votingRationale = votingRationale;
			this.consolidatedAnalysis = consolidatedAnalysis;
			this.consolidatedRootCause = consolidatedRootCause;
			this.consolidatedRemediation = consolidatedRemediation;
			this.averageConfidence = averageConfidence;
		}
		
		public boolean isPromote() {
			return promote;
		}
		
		public double getPromoteScore() {
			return promoteScore;
		}
		
		public double getRollbackScore() {
			return rollbackScore;
		}
		
		public String getVotingRationale() {
			return votingRationale;
		}
		
		public String getConsolidatedAnalysis() {
			return consolidatedAnalysis;
		}
		
		public String getConsolidatedRootCause() {
			return consolidatedRootCause;
		}
		
		public String getConsolidatedRemediation() {
			return consolidatedRemediation;
		}
		
		public int getAverageConfidence() {
			return averageConfidence;
		}
	}
	
	/**
	 * Aggregate model results using confidence-weighted voting
	 */
	public static AggregatedResult aggregate(List<ModelAnalysisResult> results) {
		if (results == null || results.isEmpty()) {
			throw new IllegalArgumentException("Cannot aggregate empty results");
		}
		
		logger.info("Aggregating {} model analysis results", results.size());
		
		// Filter out failed analyses
		List<ModelAnalysisResult> validResults = results.stream()
				.filter(r -> r.getError() == null || r.getError().isEmpty())
				.collect(Collectors.toList());
		
		if (validResults.isEmpty()) {
			throw new IllegalArgumentException("All model analyses failed");
		}
		
		// Calculate weighted scores
		double promoteScore = 0.0;
		double rollbackScore = 0.0;
		
		for (ModelAnalysisResult result : validResults) {
			double weight = result.getConfidence() / 100.0;
			if (result.isPromote()) {
				promoteScore += weight;
			} else {
				rollbackScore += weight;
			}
		}
		
		// Determine final decision
		boolean finalPromote = promoteScore > rollbackScore;
		
		// Calculate average confidence
		int avgConfidence = (int) validResults.stream()
				.mapToInt(ModelAnalysisResult::getConfidence)
				.average()
				.orElse(0);
		
		// Build voting rationale
		String rationale = buildVotingRationale(validResults, promoteScore, rollbackScore, finalPromote);
		
		// Consolidate analyses
		String consolidatedAnalysis = consolidateAnalyses(validResults);
		String consolidatedRootCause = consolidateRootCauses(validResults);
		String consolidatedRemediation = consolidateRemediations(validResults);
		
		logger.info("Aggregation complete: promote={}, promoteScore={}, rollbackScore={}, avgConfidence={}",
				finalPromote, promoteScore, rollbackScore, avgConfidence);
		
		return new AggregatedResult(
				finalPromote,
				promoteScore,
				rollbackScore,
				rationale,
				consolidatedAnalysis,
				consolidatedRootCause,
				consolidatedRemediation,
				avgConfidence
		);
	}
	
	private static String buildVotingRationale(List<ModelAnalysisResult> results,
	                                          double promoteScore, double rollbackScore,
	                                          boolean finalDecision) {
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("Confidence-weighted voting: Promote=%.2f, Rollback=%.2f. ", 
				promoteScore, rollbackScore));
		sb.append(String.format("Final decision: %s.\n\n", finalDecision ? "PROMOTE" : "ROLLBACK"));
		
		sb.append("Individual model votes:\n");
		for (ModelAnalysisResult result : results) {
			sb.append(String.format("- %s: %s (confidence: %d%%)\n",
					result.getModelName(),
					result.isPromote() ? "PROMOTE" : "ROLLBACK",
					result.getConfidence()));
		}
		
		return sb.toString();
	}
	
	private static String consolidateAnalyses(List<ModelAnalysisResult> results) {
		StringBuilder sb = new StringBuilder();
		sb.append("Multi-model analysis consensus:\n\n");
		
		for (ModelAnalysisResult result : results) {
			sb.append("--- ").append(result.getModelName()).append(" ---\n");
			sb.append(result.getAnalysis()).append("\n\n");
		}
		
		return sb.toString();
	}
	
	private static String consolidateRootCauses(List<ModelAnalysisResult> results) {
		// Find common themes in root causes
		StringBuilder sb = new StringBuilder();
		
		for (ModelAnalysisResult result : results) {
			if (result.getRootCause() != null && !result.getRootCause().isEmpty()) {
				sb.append(result.getModelName()).append(": ").append(result.getRootCause()).append("; ");
			}
		}
		
		return sb.toString();
	}
	
	private static String consolidateRemediations(List<ModelAnalysisResult> results) {
		StringBuilder sb = new StringBuilder();
		
		for (ModelAnalysisResult result : results) {
			if (result.getRemediation() != null && !result.getRemediation().isEmpty()) {
				sb.append("- ").append(result.getRemediation()).append("\n");
			}
		}
		
		return sb.toString();
	}
}
