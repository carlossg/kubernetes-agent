import urllib.request
import json
import sys

API_URL = "http://localhost:8000/v1/chat/completions"
MODEL_NAME = "gemma-3-1b-it" 

def test_native_tools():
    data = {
        "model": MODEL_NAME,
        "messages": [
            {"role": "system", "content": "You are a helpful assistant with access to tools. Use the get_weather tool to answer the user's question."},
            {"role": "user", "content": "What is the weather in London?"}
        ],
        "tools": [
            {
                "type": "function",
                "function": {
                    "name": "get_weather",
                    "description": "Get the current weather in a location",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "location": {"type": "string", "description": "The city and state, e.g. San Francisco, CA"}
                        },
                        "required": ["location"]
                    }
                }
            }
        ],
        "tool_choice": "required",  # Changed from "auto" to "required" to force tool calling
        "temperature": 0.0
    }
    
    req = urllib.request.Request(
        API_URL, 
        data=json.dumps(data).encode('utf-8'),
        headers={'Content-Type': 'application/json'}
    )
    
    try:
        with urllib.request.urlopen(req) as response:
            result = json.loads(response.read().decode('utf-8'))
            print(json.dumps(result, indent=2))
            
            message = result['choices'][0]['message']
            if 'tool_calls' in message and message['tool_calls']:
                print("\n✅ SUCCESS: Model called the tool!")
                return True
            else:
                print("\n❌ FAILURE: Model did not call the tool. Response content:")
                print(message.get('content'))
                return False
    except Exception as e:
        print(f"\n💥 ERROR: {e}")
        if hasattr(e, 'read'):
            print(e.read().decode())
        return False

if __name__ == '__main__':
    # Assume port-forward is already running (it is if we just ran the test script)
    if not test_native_tools():
        sys.exit(1)
