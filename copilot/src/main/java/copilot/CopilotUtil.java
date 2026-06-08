package copilot;

import com.google.gson.*;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import copilot.tools.api.AgentTool;
import copilot.chat.ChatMessage;

import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class CopilotUtil {

    /** The in-flight HTTP connection, if any. May be disconnected to cancel a request. */
    private static volatile HttpURLConnection activeConnection;

    /** Cache of context-window sizes keyed by "endpoint|model". Populated lazily on first use. */
    private static final Map<String, Integer> contextWindowCache = new ConcurrentHashMap<>();

    /**
     * Immediately aborts the current in-flight request (if any) by disconnecting
     * the underlying HTTP connection. Safe to call from any thread.
     */
    public static void cancelCurrentRequest() {
        HttpURLConnection conn = activeConnection;
        if (conn != null) {
            conn.disconnect();
        }
    }

    /**
     * Queries the {@code /v1/models/{model}} endpoint to discover the model's actual
     * context-window size. Results are cached per endpoint+model so the network is only
     * hit once per session. Returns 0 if the server doesn't expose the field.
     */
    public static int fetchContextWindow(String chatEndpoint, String apiKey, String model) {
        String cacheKey = chatEndpoint + "|" + model;
        Integer cached = contextWindowCache.get(cacheKey);
        if (cached != null) return cached;
        int result = doFetchContextWindow(chatEndpoint, apiKey, model);
        if (result > 0) contextWindowCache.put(cacheKey, result); // only cache successes — allow retries on failure
        return result;
    }

    private static int doFetchContextWindow(String chatEndpoint, String apiKey, String model) {
        // 1. Try GET /v1/models/{model} — OpenAI, LM Studio, Groq, Together, etc.
        int result = tryModelsEndpoint(chatEndpoint, apiKey, model);
        if (result > 0) return result;

        // 2. Fallback: Ollama /api/show — Ollama's native endpoint exposes context_length
        //    even when its OpenAI-compat /v1/models/{model} doesn't.
        return tryOllamaShow(chatEndpoint, model);
    }

    private static int tryModelsEndpoint(String chatEndpoint, String apiKey, String model) {
        try {
            String encoded = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
            String modelsUrl;
            if (chatEndpoint.contains("chat/completions")) {
                modelsUrl = chatEndpoint.replaceFirst("chat/completions.*", "models/" + encoded);
            } else {
                URL u = new URL(chatEndpoint);
                modelsUrl = u.getProtocol() + "://" + u.getHost()
                        + (u.getPort() > 0 ? ":" + u.getPort() : "")
                        + "/v1/models/" + encoded;
            }

            URL url = new URL(modelsUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
                    // Different providers use different field names
                    for (String field : new String[]{"context_window", "context_length", "max_context_length", "n_ctx"}) {
                        if (json.has(field) && json.get(field).isJsonPrimitive())
                            return json.get(field).getAsInt();
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Tries Ollama's native POST /api/show endpoint.
     * Attempts newer {"model":...} format first, falls back to older {"name":...}.
     * Priority: parameters > modelfile > model_info
     *   - parameters / modelfile reflect the user-configured num_ctx (what Ollama actually uses)
     *   - model_info reflects the base GGUF context length (often smaller than configured)
     */
    private static int tryOllamaShow(String chatEndpoint, String model) {
        try {
            URL u = new URL(chatEndpoint);
            String showUrl = u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "")
                    + "/api/show";

            // Try newer Ollama API format first, then fall back to older
            JsonObject json = postOllamaShow(showUrl, "{\"model\":\"" + model + "\"}");
            if (json == null) json = postOllamaShow(showUrl, "{\"name\":\"" + model + "\"}");
            if (json == null) return 0;

            java.util.regex.Pattern numCtxPat = java.util.regex.Pattern.compile("num_ctx\\s+(\\d+)");
            int configuredCtx = 0; // Ollama's num_ctx — what it actually allocates
            int nativeCtx     = 0; // Model's GGUF architecture limit

            // 1. parameters — user-configured "num_ctx NNNN"
            if (json.has("parameters") && json.get("parameters").isJsonPrimitive()) {
                java.util.regex.Matcher m = numCtxPat.matcher(json.get("parameters").getAsString());
                if (m.find()) { int v = Integer.parseInt(m.group(1)); if (v > 0) configuredCtx = v; }
            }

            // 2. modelfile — also contains "PARAMETER num_ctx NNNN" (same value, different source)
            if (configuredCtx == 0 && json.has("modelfile") && json.get("modelfile").isJsonPrimitive()) {
                java.util.regex.Matcher m = numCtxPat.matcher(json.get("modelfile").getAsString());
                if (m.find()) { int v = Integer.parseInt(m.group(1)); if (v > 0) configuredCtx = v; }
            }

            // 3. model_info — GGUF metadata: the model architecture's hard maximum
            if (json.has("model_info") && json.get("model_info").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry :
                        json.getAsJsonObject("model_info").entrySet()) {
                    if (entry.getKey().endsWith(".context_length") || entry.getKey().equals("context_length")) {
                        int v = entry.getValue().getAsInt();
                        if (v > 0) { nativeCtx = v; break; }
                    }
                }
            }

            // Both constraints must be known to report a meaningful value.
            // num_ctx is what Ollama allocates; native is what the model architecture supports.
            // If either is missing we genuinely don't know the true limit — return 0 so the
            // UI can display "???" rather than showing a number that is only half the picture.
            if (configuredCtx > 0 && nativeCtx > 0) return Math.min(configuredCtx, nativeCtx);
        } catch (Exception ignored) {}
        return 0;
    }

    private static JsonObject postOllamaShow(String showUrl, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(showUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return JsonParser.parseString(sb.toString()).getAsJsonObject();
            }
        } catch (Exception ignored) { return null; }
    }

    public static void showNotification(Project project, String title, String content,
                                        NotificationType type) {
        if (project == null || project.isDisposed()) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            Notification notification = new Notification(
                    "copilot.notification.group", title, content, type);
            Notifications.Bus.notify(notification, project);
        });
    }

    /**
     * Sends a streaming (SSE) chat request. Calls {@code onContentChunk} for each text delta
     * and {@code onReasoningChunk} for each reasoning delta as they arrive, then returns
     * the fully-assembled response object (same shape as {@link #sendChatRequest}).
     */
    public static JsonObject streamChatRequest(
            List<ChatMessage> messages,
            List<AgentTool> tools,
            int maxTokens,
            int contextWindowHint,
            Consumer<String> onContentChunk,
            Consumer<String> onReasoningChunk,
            BooleanSupplier isCancelled) throws IOException {

        CopilotSettings settings = CopilotSettings.getInstance();

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", settings.getModel());

        JsonArray messagesArray = new JsonArray();
        for (ChatMessage msg : messages) messagesArray.add(msg.toJson());
        requestBody.add("messages", messagesArray);

        boolean includeTools = tools != null && !tools.isEmpty();
        if (includeTools) {
            JsonArray toolsArray = new JsonArray();
            for (AgentTool tool : tools) toolsArray.add(tool.toToolDefinition());
            requestBody.add("tools", toolsArray);
            String tc = settings.getActiveAgent().toolChoice;
            requestBody.addProperty("tool_choice", (tc != null && !tc.isBlank()) ? tc : "auto");
        }

        requestBody.addProperty("temperature", 0.3);
        requestBody.addProperty("max_tokens", maxTokens);
        requestBody.addProperty("stream", true);
        // stream_options is OpenAI-specific; Mistral/Codestral rejects it with HTTP 400
        if (!isMistralEndpoint(settings.getApiEndpoint())) {
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            requestBody.add("stream_options", streamOptions);
        }
        // For Ollama: override num_ctx so it actually allocates the model's full context window.
        // Without this Ollama defaults to 2048 or 16384 regardless of model capability.
        if (contextWindowHint > 0 && isOllamaEndpoint(settings.getApiEndpoint())) {
            JsonObject ollamaOptions = new JsonObject();
            ollamaOptions.addProperty("num_ctx", contextWindowHint);
            requestBody.add("options", ollamaOptions);
        }

        // Accumulators
        StringBuilder contentBuf   = new StringBuilder();
        StringBuilder reasoningBuf = new StringBuilder();
        // Tool calls keyed by index — arguments are concatenated across chunks
        Map<Integer, JsonObject> toolCallMap = new LinkedHashMap<>();
        String[]     finishReason = {null};
        JsonObject[] usage        = {null};
        String[]     role         = {"assistant"};

        Consumer<String> dataHandler = dataLine -> {
                    if (isCancelled.getAsBoolean()) return;
                    JsonObject chunk;
                    try { chunk = JsonParser.parseString(dataLine).getAsJsonObject(); }
                    catch (Exception ignored) { return; }

                    if (chunk.has("usage") && !chunk.get("usage").isJsonNull())
                        usage[0] = chunk.getAsJsonObject("usage");

                    if (!chunk.has("choices") || chunk.getAsJsonArray("choices").isEmpty()) return;
                    JsonObject choice = chunk.getAsJsonArray("choices").get(0).getAsJsonObject();

                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull())
                        finishReason[0] = choice.get("finish_reason").getAsString();

                    if (!choice.has("delta")) return;
                    JsonObject delta = choice.getAsJsonObject("delta");

                    if (delta.has("role") && !delta.get("role").isJsonNull())
                        role[0] = delta.get("role").getAsString();

                    // Reasoning fields (Ollama / Together AI etc.)
                    for (String rKey : new String[]{"reasoning_content", "reasoning"}) {
                        if (delta.has(rKey) && !delta.get(rKey).isJsonNull()) {
                            String r = delta.get(rKey).getAsString();
                            if (!r.isEmpty()) { reasoningBuf.append(r); onReasoningChunk.accept(r); }
                        }
                    }

                    // Content
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String c = delta.get("content").getAsString();
                        if (!c.isEmpty()) { contentBuf.append(c); onContentChunk.accept(c); }
                    }

                    // Tool calls — assemble from fragments
                    if (delta.has("tool_calls")) {
                        for (JsonElement tcElem : delta.getAsJsonArray("tool_calls")) {
                            JsonObject tc  = tcElem.getAsJsonObject();
                            int idx = tc.has("index") ? tc.get("index").getAsInt() : 0;
                            JsonObject acc = toolCallMap.computeIfAbsent(idx, i -> {
                                JsonObject t = new JsonObject();
                                JsonObject fn = new JsonObject();
                                fn.addProperty("name", "");
                                fn.addProperty("arguments", "");
                                t.add("function", fn);
                                return t;
                            });
                            if (tc.has("id"))   acc.addProperty("id",   tc.get("id").getAsString());
                            if (tc.has("type")) acc.addProperty("type", tc.get("type").getAsString());
                            if (tc.has("function")) {
                                JsonObject fn    = tc.getAsJsonObject("function");
                                JsonObject accFn = acc.getAsJsonObject("function");
                                if (fn.has("name") && !fn.get("name").getAsString().isEmpty())
                                    accFn.addProperty("name", fn.get("name").getAsString());
                                if (fn.has("arguments"))
                                    accFn.addProperty("arguments",
                                            accFn.get("arguments").getAsString()
                                                    + fn.get("arguments").getAsString());
                            }
                        }
                    }
                };

        streamSSE(settings.getApiEndpoint(), settings.getApiKey(), requestBody.toString(), dataHandler);

        // Build a response object that mirrors the non-streaming shape
        JsonObject message = new JsonObject();
        message.addProperty("role", role[0]);
        message.addProperty("content", contentBuf.toString());
        if (reasoningBuf.length() > 0)
            message.addProperty("reasoning_content", reasoningBuf.toString());
        if (!toolCallMap.isEmpty()) {
            JsonArray tcArr = new JsonArray();
            toolCallMap.values().forEach(tcArr::add);
            message.add("tool_calls", tcArr);
        }

        JsonObject choiceObj = new JsonObject();
        choiceObj.add("message", message);
        if (finishReason[0] != null) choiceObj.addProperty("finish_reason", finishReason[0]);

        JsonArray choices = new JsonArray();
        choices.add(choiceObj);

        JsonObject result = new JsonObject();
        result.add("choices", choices);
        if (usage[0] != null) result.add("usage", usage[0]);
        return result;
    }

    /** Reads an SSE stream and calls {@code lineHandler} for each {@code data:} line. */
    /**
     * Ollama defaults num_ctx to 2048 regardless of model capability unless told otherwise.
     * Detect by the well-known default port (11434) or an explicit /api/ path.
     */
    private static boolean isOllamaEndpoint(String endpoint) {
        return endpoint.contains(":11434") || endpoint.contains("/api/chat") || endpoint.contains("/api/generate");
    }

    private static boolean isMistralEndpoint(String endpoint) {
        return endpoint.contains("mistral.ai") || endpoint.contains("codestral.mistral");
    }

private static void streamSSE(String endpoint, String apiKey, String body,
                                   Consumer<String> lineHandler) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        activeConnection = connection;
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.trim().isEmpty())
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(300_000);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                String errBody = "";
                InputStream errStream = connection.getErrorStream();
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errStream, StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        errBody = sb.toString();
                    } catch (Exception ignored) {}
                }
                throw new IOException("HTTP " + code + (errBody.isEmpty() ? "" : ": " + errBody));
            }

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        if (!data.isEmpty()) lineHandler.accept(data);
                    }
                }
            }
        } finally {
            activeConnection = null;
        }
    }

    /**
     * Sends a minimal chat request to verify that the endpoint/key/model are working.
     *
     * @return {@code null} on success, or a human-readable error message on failure.
     */
    @Nullable
    public static String probeConnection(String endpoint, String apiKey, String model) {
        try {
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(30_000);

            String body = "{\"model\":\"" + model + "\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                    + "\"max_tokens\":1,\"stream\":false}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
                    if (!json.has("choices")) {
                        // Some providers (e.g. native /api/chat) return a non-OpenAI-compatible format.
                        // Suggest the OpenAI-compatible endpoint if one can be inferred.
                        if (json.has("message") && json.has("done")) {
                            String suggestion = endpoint.replaceAll("/api/chat.*", "/v1/chat/completions");
                            return "The endpoint returned a response, but not in the expected OpenAI-compatible format. "
                                    + "Try using an OpenAI-compatible endpoint instead"
                                    + (suggestion.equals(endpoint) ? "." : ": " + suggestion);
                        }
                        return "Response received but 'choices' field missing. Raw: " + sb;
                    }
                    return null; // success
                }
            } else {
                InputStream errStream = conn.getErrorStream();
                String errBody = "";
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errStream, StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        errBody = sb.toString();
                    }
                }
                try {
                    JsonObject err = JsonParser.parseString(errBody).getAsJsonObject();
                    if (err.has("error")) {
                        JsonObject inner = err.getAsJsonObject("error");
                        if (inner.has("message")) return "HTTP " + code + ": " + inner.get("message").getAsString();
                    }
                } catch (Exception ignored) { /* fall through */ }
                return "HTTP " + code + (errBody.isEmpty() ? "" : ": " + errBody);
            }
        } catch (java.net.ConnectException ex) {
            return "Cannot connect to " + endpoint + ". Is the server running? (" + ex.getMessage() + ")";
        } catch (java.net.SocketTimeoutException ex) {
            return "Connection timed out. Check that the server is reachable.";
        } catch (Exception ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

}