# Enhanced Error Handling for Gemma Tool Calling

## Summary of Changes

Enhanced error handling and logging to ensure that when Gemma (or any model) fails to generate tool calls, the failure is properly logged and the model is excluded from decision-making.

## Changes Made

### 1. VllmGemma.java - Enhanced Error Logging

Added detailed error logging specifically for tool calling failures:

```java
if (!response.isSuccessful()) {
    String errorBody = response.body() != null ? response.body().string() : "No error details";
    
    // Enhanced error logging for tool calling failures
    if (response.code() == 400 && errorBody.contains("tool") || errorBody.contains("function")) {
        logger.error("❌ Gemma tool calling failure detected!");
        logger.error("   HTTP Status: {}", response.code());
        logger.error("   Error details: {}", errorBody);
        logger.error("   This likely indicates the model generated malformed tool calls.");
        logger.error("   Consider using a larger model (Gemma 2 9B/27B) or reducing tool complexity.");
    }
    // ...
}
```

### 2. VllmGemma.java - Fixed Schema Type Case Issue

Added `lowercaseSchemaTypes()` method to convert ADK's uppercase types (STRING, OBJECT) to OpenAI's lowercase format (string, object):

```java
private void lowercaseSchemaTypes(JsonNode node) {
    // Recursively converts all "type" fields to lowercase
}
```

This fixes the "Grammar error: Invalid type: OBJECT" error from vLLM.

### 3. A2AController.java - Enhanced Model Failure Logging

Added comprehensive logging when models fail:

```java
} catch (Exception e) {
    logger.error("❌ Model {} analysis failed", modelName);
    logger.error("   Error type: {}", e.getClass().getSimpleName());
    logger.error("   Error message: {}", e.getMessage());
    
    // Check if this is a tool calling related error
    if (errorMsg.contains("tool") || errorMsg.contains("function") || 
        errorMsg.contains("JSON") || errorMsg.contains("parsing")) {
        logger.error("   ⚠️  This appears to be a tool calling failure!");
        logger.error("   The model likely generated malformed tool calls or invalid JSON.");
        logger.error("   Recommendation: Use a larger model or reduce tool complexity.");
    }
    
    logger.warn("   ⚠️  Model {} will be EXCLUDED from decision-making due to failure", modelName);
}
```

### 4. A2AController.java - Decision-Making Summary

Added clear logging showing which models were used/excluded in the final decision:

```java
logger.info("📊 Decision-making summary:");
logger.info("   ✅ Models used in decision ({} total):", validResults.size());
validResults.forEach(r -> logger.info("      - {}: {} (confidence: {}%)", 
        r.getModelName(), r.isPromote() ? "PROMOTE" : "ROLLBACK", r.getConfidence()));

if (!invalidResults.isEmpty()) {
    logger.warn("   ❌ Models EXCLUDED from decision ({} total):", invalidResults.size());
    invalidResults.forEach(r -> logger.warn("      - {}: {}", 
            r.getModelName(), r.getError()));
}

logger.info("   🎯 Final decision: {} (promote score: {:.2f}, rollback score: {:.2f})", 
        aggregated.isPromote() ? "PROMOTE" : "ROLLBACK",
        aggregated.getPromoteScore(),
        aggregated.getRollbackScore());
```

## Behavior

### When a Model Fails

1. **Error is logged with clear context**:
   - Error type and message
   - Whether it's tool-calling related
   - Recommendations for resolution

2. **Model is excluded from voting**:
   - `VotingAggregator.aggregate()` filters out failed models (line 87-89)
   - Only successful models contribute to the final decision
   - Failed models are listed in `modelResults` with `error` field populated

3. **Decision continues with remaining models**:
   - If at least one model succeeds, decision is made based on successful models
   - If all models fail, a safe default is returned (promote=true, confidence=0)

## Example Log Output

```
2026-01-31T23:38:10.965 ERROR --- [nio-8082-exec-1] o.c.a.agents.k8sagent.models.VllmGemma   : vLLM API error: 400 - {"error":{"message":"Conversation roles must alternate user/assistant/user/assistant/...","type":"BadRequestError","param":null,"code":400}}
2026-01-31T23:38:10.969 ERROR --- [nio-8082-exec-1] o.c.a.agents.k8sagent.a2a.A2AController  : ❌ Model gemma-3-1b-it analysis failed
2026-01-31T23:38:10.969 ERROR --- [nio-8082-exec-1] o.c.a.agents.k8sagent.a2a.A2AController  :    Error type: RuntimeException
2026-01-31T23:38:10.970 ERROR --- [nio-8082-exec-1] o.c.a.agents.k8sagent.a2a.A2AController  :    Error message: java.io.IOException: vLLM API error: 400 - {"error":{"message":"Conversation roles must alternate user/assistant/user/assistant/...","type":"BadRequestError","param":null,"code":400}}
2026-01-31T23:38:10.970  WARN --- [nio-8082-exec-1] o.c.a.agents.k8sagent.a2a.A2AController  :    ⚠️  Model gemma-3-1b-it will be EXCLUDED from decision-making due to failure
```

## Testing

The enhanced logging can be tested with:

```bash
cd kubernetes-agent
bash test-gemma-tools.sh
```

When Gemma fails (as expected with complex multi-tool scenarios), the logs clearly show:
- The specific error from vLLM
- That the model will be excluded
- Clear visual indicators (❌, ⚠️) for easy scanning

## Notes

- The `VotingAggregator` already had logic to filter failed models, this enhancement adds visibility
- Failed models still appear in the API response's `modelResults` array with their error details
- The multi-model voting system is robust to individual model failures
