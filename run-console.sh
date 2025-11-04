#!/bin/bash

# Run the Kubernetes Agent in console mode
# Usage:
#   ./run-console.sh           # Run with default profile (gemini)
#   ./run-console.sh openai    # Run with OpenAI profile
#   ./run-console.sh gemini    # Run with Gemini profile

PROFILE="${1:-gemini}"

if [[ "$PROFILE" != "openai" && "$PROFILE" != "gemini" ]]; then
    echo "❌ Invalid profile: $PROFILE"
    echo "Usage: $0 [openai|gemini]"
    echo "  openai - Use OpenAI model"
    echo "  gemini - Use Gemini model (default)"
    exit 1
fi

echo "🚀 Starting Kubernetes Agent in console mode"
echo "📊 Profile: $PROFILE"
echo ""

./mvnw quarkus:dev -Drun.mode=console -Dquarkus.profile=$PROFILE
