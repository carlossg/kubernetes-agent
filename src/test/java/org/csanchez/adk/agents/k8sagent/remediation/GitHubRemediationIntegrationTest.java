package org.csanchez.adk.agents.k8sagent.remediation;

import org.csanchez.adk.agents.k8sagent.a2a.ModelAnalysisResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-API integration tests for GitHubPRTool's issue + PR creation.
 *
 * Gated on GITHUB_TOKEN; skipped without it. The test repo defaults to
 * carlossg/kubernetes-agent and can be overridden via TEST_GITHUB_REPO_URL.
 * Both tests create real GitHub artefacts and clean them up in @AfterEach
 * (closing issues/PRs, deleting branches). Titles are prefixed [TEST] so any
 * survivors are easy to spot.
 */
@EnabledIfEnvironmentVariable(named = "GITHUB_TOKEN", matches = ".*")
class GitHubRemediationIntegrationTest {

	private static final String DEFAULT_TEST_REPO = "https://github.com/carlossg/kubernetes-agent";

	private final String repoUrl = System.getenv()
			.getOrDefault("TEST_GITHUB_REPO_URL", DEFAULT_TEST_REPO);

	private GHRepository repo;
	private final List<GHIssue> issuesToClose = new ArrayList<>();
	private final List<GHPullRequest> prsToClose = new ArrayList<>();
	private final List<String> branchesToDelete = new ArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		GitHub gh = new GitHubBuilder()
				.withOAuthToken(System.getenv("GITHUB_TOKEN"))
				.build();
		String repoName = repoUrl
				.replace("https://github.com/", "")
				.replace(".git", "");
		repo = gh.getRepository(repoName);
	}

	@AfterEach
	void cleanup() {
		for (GHPullRequest pr : prsToClose) {
			try {
				if (!pr.isMerged() && pr.getState().toString().equalsIgnoreCase("OPEN")) {
					pr.close();
				}
			} catch (IOException ignored) {
			}
		}
		for (GHIssue issue : issuesToClose) {
			try {
				if (issue.getState().toString().equalsIgnoreCase("OPEN")) {
					issue.close();
				}
			} catch (IOException ignored) {
			}
		}
		for (String branch : branchesToDelete) {
			try {
				GHRef ref = repo.getRef("heads/" + branch);
				ref.delete();
			} catch (IOException ignored) {
			}
		}
	}

	@Test
	void createRollbackIssue_addsJulesLabel() throws IOException {
		ModelAnalysisResult result = new ModelAnalysisResult();
		result.setModelName("gemini-3-flash-preview");
		result.setAnalysis("Test analysis: canary failing on db connection refused");
		result.setRootCause("DB host misconfigured to localhost");
		result.setRemediation("Point DB_HOST to the cluster service");
		result.setPromote(false);
		result.setConfidence(95);
		result.setExecutionTimeMs(1234);

		String marker = UUID.randomUUID().toString().substring(0, 8);
		GHIssue issue = GitHubPRTool.createRollbackIssue(
				repoUrl,
				"[TEST] canary-demo-" + marker,
				"default",
				List.of(result),
				0.0,
				0.95,
				"Test voting rationale (test marker " + marker + ")");
		issuesToClose.add(issue);

		assertNotNull(issue, "createRollbackIssue must return the created issue");
		Set<String> labels = issue.getLabels().stream()
				.map(l -> l.getName())
				.collect(Collectors.toSet());
		assertTrue(labels.contains("jules"),
				"Rollback issue must carry 'jules' label so Jules picks it up. Got: " + labels);
		assertTrue(labels.contains("canary-rollback"),
				"Rollback issue must carry 'canary-rollback'. Got: " + labels);
		assertTrue(labels.contains("automated"),
				"Rollback issue must carry 'automated'. Got: " + labels);
		assertTrue(issue.getTitle().contains("[TEST] canary-demo-" + marker),
				"Issue title must include the rollout name");
	}

	@Test
	void createRemediationPullRequest_doesNotAddJulesLabel() throws IOException {
		String marker = UUID.randomUUID().toString().substring(0, 8);
		String branch = "test/jules-label-check-" + marker;
		String baseBranch = repo.getDefaultBranch();
		String baseSha = repo.getBranch(baseBranch).getSHA1();

		// Create the head branch off the default branch.
		repo.createRef("refs/heads/" + branch, baseSha);
		branchesToDelete.add(branch);

		// Commit a tiny marker file so the PR has a diff.
		String testFile = ".test-artifacts/jules-pr-test-" + marker + ".md";
		repo.createContent()
				.branch(branch)
				.path(testFile)
				.content("Transient marker for jules-label PR test. Safe to delete.\n")
				.message("test: marker for jules-label PR test (" + marker + ")")
				.commit();

		GHPullRequest pr = GitHubPRTool.createRemediationPullRequest(
				repoUrl,
				branch,
				baseBranch,
				"[TEST] PR label check " + marker,
				"Automated test PR — verifies remediation PRs do not carry the 'jules' label. Will be closed.");
		prsToClose.add(pr);

		assertNotNull(pr, "createRemediationPullRequest must return the created PR");
		Set<String> labels = pr.getLabels().stream()
				.map(l -> l.getName())
				.collect(Collectors.toSet());
		assertFalse(labels.contains("jules"),
				"Remediation PR must NOT carry 'jules' label (that belongs to the issue). Got: " + labels);
	}
}
