# Shared config for model test scripts. Source with: . "$(dirname "$0")/test-config.sh"
# Defines: CONTEXT, GEMMA_NAMESPACE, AGENT_PORT, TEST_PROMPT, TEST_CONTEXT

CONTEXT="${TEST_CONTEXT_K8S:-gke_api-project-642841493686_us-central1_autopilot-cluster-1}"
GEMMA_NAMESPACE="gemma-system"
AGENT_PORT="8080"

TEST_PROMPT='You are a Kubernetes SRE. Check canary pod logs for errors.

STEPS:
1. Call list_pods with namespace and canarySelector from context
2. For each pod, call read_pod_logs with namespace and pod name
3. Check logs:
   - "panic:" or "runtime error:" = ERROR
   - "200 - red" = HEALTHY
4. Report each pod: "Pod <name>: <healthy|has errors>. Logs show: <quote>"

Decision: If any pod has panic/error → promote=false

Example response:
"Pod canary-demo-xyz: has errors. Logs show: runtime error: index out of range [0] at line 195
Pod canary-demo-abc: healthy. Logs show: 200 - red"

JSON format:
{
"analysis": "<pod analysis>",
"rootCause": "<error from logs>",
"remediation": "<fix>",
"prLink": null,
"promote": true or false,
"confidence": "0-100"
}'

TEST_CONTEXT='{"namespace": "default", "rolloutName": "canary-demo", "stableSelector": "role=stable", "canarySelector": "role=canary"}'
