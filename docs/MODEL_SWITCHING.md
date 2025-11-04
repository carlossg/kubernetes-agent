# Model Switching Guide

This guide explains how to switch between OpenAI and Google Gemini models in the Kubernetes Agent.

## Overview

The application supports both OpenAI and Google Gemini AI models through Quarkus profiles. Both extensions are enabled in the project, and you can switch between them by setting the appropriate profile.

## Configuration

### Profiles

Two profiles are available:

- **`gemini`** (default): Uses Google Gemini AI (gemini-2.0-flash-exp)
- **`openai`**: Uses OpenAI (gpt-4o)

### Environment Variables

Configure the following environment variables based on your chosen profile:

#### For Gemini Profile
```bash
GEMINI_API_KEY=your-gemini-api-key
```

#### For OpenAI Profile
```bash
OPENAI_API_KEY=your-openai-api-key
OPENAI_MODEL=gpt-4o  # Optional, defaults to gpt-4o
OPENAI_BASE_URL=https://api.openai.com/v1  # Optional, defaults to OpenAI API
```

## Usage

### Local Development

#### Using Gemini (Default)
```bash
# Set environment variables in .env file
GEMINI_API_KEY=your-gemini-api-key

# Run the application (gemini is the default profile)
./mvnw quarkus:dev
```

#### Using OpenAI
```bash
# Set environment variables in .env file
OPENAI_API_KEY=your-openai-api-key

# Run with openai profile
./mvnw quarkus:dev -Dquarkus.profile=openai
```

### Production Deployment

#### Using Kubernetes Secrets

1. Create a secret from the template:
```bash
cp deployment/secret.yaml.template deployment/secret.yaml
```

2. Edit [`deployment/secret.yaml`](../deployment/secret.yaml) and set your API keys:
```yaml
stringData:
  # For Gemini
  gemini_api_key: "your-gemini-api-key"
  
  # For OpenAI
  openai_api_key: "your-openai-api-key"
  openai_model: "gpt-4o"
  openai_base_url: "https://api.openai.com/v1"
```

3. Apply the secret:
```bash
kubectl apply -f deployment/secret.yaml
```

4. Deploy with the desired profile by setting the `QUARKUS_PROFILE` environment variable in your deployment:
```yaml
env:
  - name: QUARKUS_PROFILE
    value: "prod,openai"  # or "prod,gemini"
```

#### Using Environment Variables

Set the profile via environment variable:
```bash
export QUARKUS_PROFILE=prod,openai  # or prod,gemini
```

### Console Mode

When running in console mode:

```bash
# With Gemini (default)
./run-console.sh

# With OpenAI
QUARKUS_PROFILE=openai ./run-console.sh
```

## Configuration Details

### Application Properties

The profile-specific configurations are defined in [`application.properties`](../src/main/resources/application.properties):

- **Gemini Profile** (`%gemini`): Configures the Google AI Gemini extension
- **OpenAI Profile** (`%openai`): Configures the OpenAI extension

### Model Settings

#### Gemini Configuration
- Model: `gemini-2.5-flash`
- Timeout: 60 seconds
- Request/Response logging: Enabled

#### OpenAI Configuration
- Model: `gpt-4o` (configurable via `OPENAI_MODEL`)
- Base URL: `https://api.openai.com/v1` (configurable via `OPENAI_BASE_URL`)
- Timeout: 60 seconds
- Request/Response logging: Enabled

## Testing

To verify your configuration:

1. Check the logs on startup to confirm which model is being used
2. The application will log the active profile and model configuration
3. Test with a simple query to ensure the AI model responds correctly

## Troubleshooting

### Profile Not Switching

Ensure the `QUARKUS_PROFILE` environment variable is set correctly:
```bash
echo $QUARKUS_PROFILE
```

### API Key Issues

- Verify your API keys are valid and have the necessary permissions
- Check that the correct environment variable is set for your chosen profile
- Review application logs for authentication errors

### Model Not Found

- Ensure the model name in your configuration matches available models
- For OpenAI: Check that your API key has access to the specified model
- For Gemini: Verify the model name is correct (e.g., `gemini-2.5-flash`)

## Additional Resources

- [Quarkus Profiles Documentation](https://quarkus.io/guides/config-reference#profiles)
- [LangChain4j Quarkus Extension](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- [OpenAI API Documentation](https://platform.openai.com/docs)
- [Google Gemini API Documentation](https://ai.google.dev/docs)