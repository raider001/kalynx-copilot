# Kalynx Copilot

An agentic AI coding assistant for IntelliJ IDEA. Point it at any OpenAI-compatible API — cloud or local — and it will read, write, search, and build your project autonomously until the task is done.

## Installation

1. Download `Kalynx-Copilot-1.0.0.zip` from the [latest release](https://github.com/raider001/kalynx-copilot/releases/latest).
2. Locate your IntelliJ IDEA plugins directory:
   - **Windows:** `%APPDATA%\JetBrains\IntelliJIdea<version>\plugins`
   - **macOS:** `~/Library/Application Support/JetBrains/IntelliJIdea<version>/plugins`
   - **Linux:** `~/.local/share/JetBrains/IntelliJIdea<version>/plugins`
3. Extract the zip into that directory. You should end up with a `copilot` folder inside `plugins`.
4. Restart IntelliJ IDEA.
5. Open **Tools → Kalynx Copilot Settings** and enter your API details.

The plugin panel opens on the right side of the IDE under **Kalynx Copilot**.

## Configuration

Open **Tools → Kalynx Copilot Settings** to configure one or more agent profiles.

| Setting | Description |
|---|---|
| **API Endpoint** | Full URL of the chat-completions endpoint (default: `https://api.openai.com/v1/chat/completions`) |
| **API Key** | Bearer token — leave blank for local models (Ollama, LM Studio) |
| **Model** | Model identifier, e.g. `gpt-4o`, `claude-sonnet-4-6`, `qwen3-coder` |
| **Max Iterations** | Agentic loop cap before the agent gives up (default: 100) |
| **Max Output Tokens** | Per-response token limit — raise to 32768+ for reasoning models (default: 16384) |
| **Request Timeout** | Seconds to wait for a single response, 0 = unlimited (default: 300) |
| **Tool Choice** | `auto` (default), `required`, or `none` |
| **Parse Text Tool Calls** | Enable for local models that output tool calls as JSON in code fences rather than via the API field |
| **Compression Threshold** | Auto-compress conversation history above this token count, 0 = disabled |

You can define multiple agent profiles and switch between them in the settings panel.

## Supported Providers

Works with any OpenAI-compatible chat-completions API:

- **OpenAI** — GPT-4o, o3, etc.
- **Anthropic** — via an OpenAI-compatible proxy
- **Google** — Gemini via OpenAI-compatible endpoint
- **Local** — Ollama, LM Studio, llama.cpp with an OpenAI-compatible server

## Features

- **Agentic task loop** — the model works through a structured phase pipeline (Analyse → Plan → Implement → Verify → Done) with automatic stuck detection and loop prevention
- **File tools** — read, create, and edit files with inline IDE validation after each change
- **Build & test tools** — run Gradle/Maven builds and tests; errors are fed back to the model automatically
- **Dynamic context** — pin files or folders into the system message so the model always sees current content
- **Plan management** — the model creates and updates a structured plan document as it works
- **Persistent scratchpad** — short working notes survive across turns
- **Ask-user escape hatch** — when stuck the model surfaces a structured question instead of looping

## Building from Source

Requires JDK 17+ and the IntelliJ Platform Plugin SDK.

```bash
./gradlew :copilot:buildPlugin
```

Output: `copilot/build/distributions/copilot-1.0.0.zip`
