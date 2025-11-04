package org.csanchez.rollout.k8sagent.remediation;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Quarkus REST Client for GitHub API
 */
@RegisterRestClient(configKey = "github-api")
@Path("/repos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface GitHubRestClient {

    @GET
    @Path("/{owner}/{repo}")
    GitHubRepository getRepository(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @HeaderParam("Authorization") String authorization
    );

    @POST
    @Path("/{owner}/{repo}/pulls")
    GitHubPullRequest createPullRequest(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @HeaderParam("Authorization") String authorization,
            CreatePullRequestRequest request
    );

    // DTOs
    record GitHubRepository(
            String name,
            String full_name,
            String default_branch,
            String html_url
    ) {}

    record GitHubPullRequest(
            int number,
            String html_url,
            String state,
            String title
    ) {}

    record CreatePullRequestRequest(
            String title,
            String head,
            String base,
            String body
    ) {}
}
