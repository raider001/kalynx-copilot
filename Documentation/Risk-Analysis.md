# Risk Analysis & Mitigation

## Critical Risks Assessment

---

## 1. Path Traversal Vulnerability 🔴 CRITICAL

### Risk Description
The `PathGuard` implementation has fundamental flaws allowing directory traversal attacks.

### Current Implementation (VULNERABLE)
```java
public static Path resolve(String basePath, String userPath) {
    Path base     = Path.of(basePath).normalize().toAbsolutePath();
    Path resolved = base.resolve(userPath).normalize().toAbsolutePath();
    if (!resolved.startsWith(base)) {
        throw new SecurityException("Access denied...");
    }
    return resolved;
}
```

### Attack Vectors

| Attack | Description | Impact |
|--------|-------------|--------|
| `../../../etc/passwd` | Directory traversal via `..` | Read system files |
| Symlink following | User creates symlink in project | Bypass restrictions |
| Windows UNC paths | `\\server\share\file` | External file access |
| URL encoding | `%2e%2e/` for `..` | Evasion |

### Mitigation Strategy

```java
public class SecurePathResolver {
    
    public static String resolve(String basePath, String userPath) {
        // 1. Normalize and validate inputs
        String base = Path.of(basePath).normalize().toString();
        String path = Path.of(userPath).normalize().toString();
        
        // 2. Check for absolute paths (reject unless explicitly allowed)
        if (path.startsWith("/") || path.contains(":")) {
            throw new SecurityException("Absolute paths not allowed");
        }
        
        // 3. Check for parent directory references
        if (path.contains("..") || path.contains("./")) {
            throw new SecurityException("Parent directory references not allowed");
        }
        
        // 4. Validate against whitelist of allowed extensions
        if (!isAllowedExtension(path)) {
            throw new SecurityException("File type not allowed");
        }
        
        // 5. Construct final path
        String resolved = Path.of(base, path).normalize().toString();
        
        // 6. Final verification - ensure still within base
        if (!resolved.startsWith(base + File.separator) && 
            !resolved.equals(base)) {
            throw new SecurityException("Path outside allowed directory");
        }
        
        return resolved;
    }
    
    private static boolean isAllowedExtension(String path) {
        String ext = Files.getFileExtension(path);
        Set<String> allowed = Set.of(
            "java", "kt", "py", "js", "ts", 
            "xml", "json", "md", "txt"
        );
        return allowed.contains(ext.toLowerCase());
    }
}
```

### Verification Steps
- [ ] Test with `../` sequences
- [ ] Test with symlinks
- [ ] Test with absolute paths
- [ ] Test with various encodings
- [ ] Audit all uses of PathGuard

---

## 2. No Input Validation on Tool Parameters 🔴 CRITICAL

### Risk Description
Tools receive JSON from LLM without validation, enabling DoS and injection attacks.

### Vulnerable Code Pattern
```java
// BEFORE - NO VALIDATION
public String execute(JsonObject params, Project project) {
    String path = params.get("file_path").getAsString();
    String content = params.get("content").getAsString();
    // No size checks, no type validation...
}
```

### Attack Scenarios

| Attack | Payload | Impact |
|--------|---------|--------|
| DoS - Large file | `{"file": "x.java", "content": "<10MB>"}` | Memory exhaustion |
| Regex DoS | `{"query": "(a+)+$"}` | CPU exhaustion |
| Command injection | `{"filter": "MyClass; rm -rf /"}` | RCE via shell |

### Mitigation Strategy

```java
public class ToolParameterValidator {
    
    private static final int MAX_STRING_LENGTH = 100_000;
    private static final int MAX_ARRAY_SIZE = 1000;
    private static final Set<String> SAFE_REGEX_FLAGS = 
        Set.of("i", "m", "s");
    
    public static void validateToolCall(String toolName, JsonObject params) {
        switch (toolName) {
            case "replace_in_file":
                validateReplaceInFile(params);
                break;
            case "search_in_files":
                validateSearchInFiles(params);
                break;
            case "run_tests":
                validateRunTests(params);
                break;
            default:
                // Validate common fields
                validateCommonFields(params);
        }
    }
    
    private static void validateReplaceInFile(JsonObject params) {
        // Path validation
        String path = getStringParam(params, "file_path");
        if (path == null || path.isEmpty()) {
            throw new ValidationException("file_path is required");
        }
        if (path.length() > 500) {
            throw new ValidationException("file_path too long");
        }
        
        // Content validation
        String oldCode = getStringParam(params, "old_code", "");
        String newCode = getStringParam(params, "new_code", "");
        
        if (oldCode.length() > MAX_STRING_LENGTH) {
            throw new ValidationException("old_code exceeds size limit");
        }
        if (newCode.length() > MAX_STRING_LENGTH) {
            throw new ValidationException("new_code exceeds size limit");
        }
    }
    
    private static void validateSearchInFiles(JsonObject params) {
        String query = getStringParam(params, "query");
        if (query == null || query.isEmpty()) {
            throw new ValidationException("query is required");
        }
        
        // Prevent regex DoS
        boolean useRegex = getBooleanParam(params, "use_regex", false);
        if (useRegex) {
            validateSafeRegex(query);
        }
    }
    
    private static void validateRunTests(JsonObject params) {
        String filter = getStringParam(params, "filter", "");
        
        // Whitelist filter format
        if (!filter.isEmpty()) {
            // Only allow: ClassName, ClassName#method, package.*
            if (!filter.matches("^[A-Za-z0-9_#.*]+$")) {
                throw new ValidationException("Invalid filter format");
            }
        }
    }
    
    private static void validateSafeRegex(String pattern) {
        // Check for dangerous patterns
        String dangerousPatterns = 
            "(\\(.+\\)+\\*|\\{\\d+,?\\}\\*|.*\\[.*\\].*\\*).*";
        
        if (pattern.matches(dangerousPatterns)) {
            throw new ValidationException("Potentially dangerous regex pattern");
        }
    }
    
    private static void validateCommonFields(JsonObject params) {
        // Check for unexpected fields
        Set<String> allowedFields = getAllowedFields(params.get("tool").getAsString());
        for (String field : params.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new ValidationException("Unexpected field: " + field);
            }
        }
    }
    
    private static String getStringParam(JsonObject params, String name) {
        return params.has(name) && !params.get(name).isJsonNull()
            ? params.get(name).getAsString() : null;
    }
    
    private static boolean getBooleanParam(JsonObject params, String name, boolean defaultValue) {
        return params.has(name) && params.get(name).isJsonPrimitive()
            ? params.get(name).getAsBoolean() : defaultValue;
    }
    
    private static Set<String> getAllowedFields(String toolName) {
        // Return allowed fields for each tool
        return switch (toolName) {
            case "replace_in_file" -> Set.of("file_path", "old_code", "new_code");
            case "search_in_files" -> Set.of("query", "use_regex", "file_extension");
            default -> Set.of();
        };
    }
}

// Usage in tool execution
public String execute(JsonObject params, Project project) {
    // Validate BEFORE processing
    ToolParameterValidator.validateToolCall(getName(), params);
    
    // Safe to proceed...
    String path = params.get("file_path").getAsString();
    // ...
}
```

### Verification Steps
- [ ] Test with oversized inputs
- [ ] Test with regex DoS patterns
- [ ] Test with malformed JSON
- [ ] Test with missing required fields

---

## 3. Race Conditions in ContextManager 🟡 HIGH

### Risk Description
Concurrent access to `entries` list without proper synchronization.

### Problematic Code
```java
// BEFORE - NOT THREAD-SAFE
private final List<WatchedEntry> entries = new CopyOnWriteArrayList<>();

public void addEntry(String path, boolean isFolder) {
    // Race: check-then-act not atomic
    if (entries.stream().anyMatch(e -> e.path.equals(path))) return;
    entries.add(new WatchedEntry(path, isFolder));  // May add duplicate
}

public void removeEntry(WatchedEntry entry) {
    entries.remove(entry);  // May fail silently
}
```

### Mitigation Strategy

```java
public class ContextManager {
    
    private final List<WatchedEntry> entries = new CopyOnWriteArrayList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    public void addEntry(String path, boolean isFolder) {
        // Use write lock for modifications
        lock.writeLock().lock();
        try {
            // Atomic check-and-add
            String normalizedPath = normalizePath(path);
            
            boolean exists = entries.stream()
                .anyMatch(e -> e.path.equals(normalizedPath));
            
            if (!exists) {
                entries.add(new WatchedEntry(normalizedPath, isFolder));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public void removeEntry(WatchedEntry entry) {
        lock.writeLock().lock();
        try {
            boolean removed = entries.remove(entry);
            if (!removed) {
                // Log warning - entry not found
                System.err.println("Attempted to remove non-existent entry: " + entry);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    public List<WatchedEntry> getEntries() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(entries);  // Return copy
        } finally {
            lock.readLock().unlock();
        }
    }
    
    private String normalizePath(String path) {
        return path.replace("\\", "/");
    }
}
```

### Verification Steps
- [ ] Test concurrent additions
- [ ] Test concurrent removals
- [ ] Test mixed operations
- [ ] Verify no duplicates in list

---

## 4. Process Execution Without Timeout 🟡 HIGH

### Risk Description
Process calls can hang indefinitely without proper timeout handling.

### Problematic Code
```java
// BEFORE - NO TIMEOUT
Process process = Runtime.getRuntime().exec(command);
process.waitFor();  // Can block forever!
String output = readAll(process.getInputStream());
```

### Mitigation Strategy

```java
public class SafeProcessRunner {
    
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    
    public static ProcessResult run(String[] command, File workingDir, 
                                     int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);  // Merge stderr into stdout
        
        try {
            Process process = pb.start();
            
            // Read output in separate threads to prevent blocking
            StringBuilder stdout = new StringBuilder();
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = 
                        new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });
            
            outputThread.start();
            
            // Wait with timeout
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                return ProcessResult.timeout(command, timeoutSeconds);
            }
            
            outputThread.join();  // Ensure all output captured
            
            int exitCode = process.exitValue();
            return new ProcessResult(exitCode, stdout.toString(), null);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProcessResult.interrupted(command);
        } catch (IOException e) {
            return ProcessResult.error(command, e.getMessage());
        }
    }
    
    public static class ProcessResult {
        private final int exitCode;
        private final String output;
        private final boolean timedOut;
        
        public static ProcessResult timeout(String[] cmd, int seconds) {
            return new ProcessResult(-1, 
                "Process timed out after " + seconds + " seconds", 
                true);
        }
        
        public static ProcessResult interrupted(String[] cmd) {
            return new ProcessResult(-1, "Process was interrupted", false);
        }
        
        public static ProcessResult error(String[] cmd, String message) {
            return new ProcessResult(-1, "Error: " + message, false);
        }
    }
}
```

### Verification Steps
- [ ] Test with long-running process
- [ ] Verify timeout kills process
- [ ] Test with process that produces no output
- [ ] Test interruption handling

---

## 5. No Rate Limiting on LLM Calls 🟡 HIGH

### Risk Description
Unlimited API calls can exhaust quotas and incur costs.

### Problematic Code
```java
// BEFORE - NO RATE LIMITING
while (iterations < maxIterations) {
    response = CopilotUtil.streamChatRequest(...);
    iterations++;
}
```

### Mitigation Strategy

```java
public class RateLimitedAgentSession {
    
    private final AgentSession delegate;
    private final RateLimiter rateLimiter;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicLong lastResetTime = new AtomicLong(System.currentTimeMillis());
    
    public RateLimitedAgentSession(AgentSession session, 
                                    int callsPerMinute) {
        this.delegate = session;
        this.rateLimiter = RateLimiter.create(callsPerMinute);
    }
    
    public String chat(String userMessage, AgentCallback callback) {
        // Check rate limit before making call
        if (!rateLimiter.tryAcquire()) {
            long waitTime = rateLimiter.getWaitTime();
            throw new RateLimitException(
                "Rate limit exceeded. Wait " + waitTime + "ms before retrying."
            );
        }
        
        // Track calls
        int currentCalls = callCount.incrementAndGet();
        
        // Reset counter periodically
        if (System.currentTimeMillis() - lastResetTime.get() > 60_000) {
            callCount.set(0);
            lastResetTime.set(System.currentTimeMillis());
        }
        
        return delegate.chat(userMessage, new RateLimitedCallback(callback));
    }
    
    private class RateLimitedCallback implements AgentCallback {
        private final AgentCallback delegate;
        
        public RateLimitedCallback(AgentCallback delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public void onUsage(int promptTokens, int completionTokens, 
                           int contextWindow) {
            // Track token usage for more granular limiting
            delegate.onUsage(promptTokens, completionTokens, contextWindow);
        }
        
        // Delegate other callbacks...
    }
    
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }
}

// Usage
AgentSession baseSession = new AgentSession(config);
RateLimitedAgentSession limitedSession = 
    new RateLimitedAgentSession(baseSession, 10);  // 10 calls/minute

try {
    String response = limitedSession.chat("Your prompt", callback);
} catch (RateLimitException e) {
    System.err.println(e.getMessage());
}
```

### Verification Steps
- [ ] Test rate limiting works correctly
- [ ] Verify reset timer functions properly
- [ ] Test concurrent sessions don't interfere

---

## 6. Null Safety Issues 🟡 HIGH

### Risk Description
Many methods lack null checks, causing NPEs.

### Problematic Code
```java
// BEFORE - NO NULL CHECKS
public String buildContextBlock() {
    String basePath = project.getBasePath();  // Can be null!
    
    for (WatchedEntry entry : entries) {  // Can be null!
        // ...
    }
}
```

### Mitigation Strategy

```java
public class SafeNullHandling {
    
    public static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " cannot be null");
        }
        return value;
    }
    
    public static <T> T defaultIfNull(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }
    
    public static String safeToString(Object value) {
        return value != null ? value.toString() : "";
    }
}

// Usage throughout codebase
public String buildContextBlock() {
    String basePath = requireNonNull(project.getBasePath(), "project.basePath");
    
    List<WatchedEntry> entriesCopy = defaultIfNull(entries, Collections.emptyList());
    
    StringBuilder sb = new StringBuilder();
    for (WatchedEntry entry : entriesCopy) {
        // Safe to use entry - never null
    }
    
    return sb.toString();
}
```

---

## 7. Incomplete Rollback on Error 🟡 HIGH

### Risk Description
File modifications occur before validation, leaving system in inconsistent state.

### Problematic Code
```java
// BEFORE - NO ROLLBACK
public String execute(JsonObject params, Project project) {
    // Write to disk
    Files.writeString(path, newContent);
    
    // Register in review panel
    ChangeReviewManager.getInstance(project).addChange(change);
    
    // If this fails, file is modified but not tracked!
    return "Success";
}
```

### Mitigation Strategy

```java
public class TransactionalFileOperation {
    
    public static String executeWithRollback(
            FileOperation operation,
            Project project) throws Exception {
        
        // Step 1: Validate (before any changes)
        if (!operation.canExecute()) {
            throw new ValidationException("Cannot execute operation");
        }
        
        // Step 2: Execute
        String result = operation.execute();
        
        // Step 3: Register change (if execution succeeded)
        try {
            ChangeReviewManager.getInstance(project).addChange(
                operation.getPendingChange()
            );
        } catch (Exception registrationError) {
            // Rollback on failure to register
            operation.rollback();
            throw new RegistrationException(
                "Failed to register change", 
                registrationError
            );
        }
        
        return result;
    }
    
    public interface FileOperation {
        boolean canExecute() throws Exception;
        String execute() throws Exception;
        PendingChange getPendingChange();
        void rollback() throws Exception;
    }
}

// Usage
public String replaceInFile(JsonObject params, Project project) {
    String path = params.get("file_path").getAsString();
    String oldCode = params.get("old_code").getAsString();
    String newCode = params.get("new_code").getAsString();
    
    FileOperation op = new FileOperation() {
        private String originalContent;
        
        @Override
        public boolean canExecute() throws Exception {
            // Validate before any changes
            Path filePath = Paths.get(path);
            if (!Files.exists(filePath)) {
                throw new ValidationException("File not found: " + path);
            }
            
            originalContent = Files.readString(filePath, UTF_8);
            return originalContent.contains(oldCode);
        }
        
        @Override
        public String execute() throws Exception {
            // Make the change
            String newContent = originalContent.replace(oldCode, newCode);
            Files.writeString(Paths.get(path), newContent, UTF_8);
            return "Replaced successfully";
        }
        
        @Override
        public PendingChange getPendingChange() {
            return new PendingChange(
                path,
                path,
                originalContent,
                originalContent.replace(oldCode, newCode),
                ChangeType.MODIFY
            );
        }
        
        @Override
        public void rollback() throws Exception {
            // Restore original content
            Files.writeString(Paths.get(path), originalContent, UTF_8);
        }
    };
    
    return TransactionalFileOperation.executeWithRollback(op, project);
}
```

---

## Risk Summary Matrix

| Risk | Severity | Likelihood | Mitigation Status |
|------|----------|------------|-------------------|
| Path traversal | CRITICAL | HIGH | ✅ Addressed |
| No input validation | CRITICAL | MEDIUM | ✅ Addressed |
| Race conditions | HIGH | LOW | ⚠️ Partial |
| Process timeout | HIGH | MEDIUM | ✅ Addressed |
| Rate limiting | MEDIUM | MEDIUM | ✅ Addressed |
| Null safety | HIGH | LOW | ⚠️ Partial |
| Incomplete rollback | HIGH | LOW | ✅ Addressed |

---

## Verification Checklist

### Security Tests
- [ ] Path traversal attacks blocked
- [ ] Input validation rejects invalid data
- [ ] Rate limiting prevents abuse
- [ ] Process timeouts work correctly

### Functional Tests
- [ ] Normal operations still work
- [ ] Error cases handled gracefully
- [ ] Rollback works on failure
- [ ] Race conditions don't corrupt state

### Integration Tests
- [ ] IntelliJ plugin works end-to-end
- [ ] Eclipse integration functional
- [ ] No regressions in existing functionality

---

## Priority Fix Order

1. **CRITICAL** - Path traversal (security vulnerability)
2. **CRITICAL** - Input validation (DoS/RCE prevention)
3. **HIGH** - Process timeout (hang prevention)
4. **HIGH** - Incomplete rollback (consistency)
5. **MEDIUM** - Rate limiting (cost/abuse prevention)
6. **HIGH** - Null safety (crash prevention)
7. **MEDIUM** - Race conditions (edge cases)

---

*This risk analysis should be reviewed and updated regularly as new code is added.*
