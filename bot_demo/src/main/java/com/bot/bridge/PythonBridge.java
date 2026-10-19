package com.bot.bridge;

import com.bot.model.CrawlResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PythonBridge {
    private final String pythonExecutable;
    private final String scriptPath;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;
    private final boolean allowLocalMock;
    private final String accuweatherApiKey;
    private final String accuweatherLocationKey;
    private final ExecutorService streamPool;

    public PythonBridge(String customPythonCmd, String scriptPath) {
        this(customPythonCmd, scriptPath, 30, false, "", "353412");
    }

    public PythonBridge(String customPythonCmd, String scriptPath, int timeoutSeconds,
                        boolean allowLocalMock, String accuweatherApiKey, String accuweatherLocationKey) {
        this.scriptPath = scriptPath;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
        this.allowLocalMock = allowLocalMock;
        this.accuweatherApiKey = accuweatherApiKey != null ? accuweatherApiKey : "";
        this.accuweatherLocationKey = accuweatherLocationKey != null ? accuweatherLocationKey : "353412";
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.pythonExecutable = resolvePythonCommand(customPythonCmd);
        this.streamPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "PythonBridge-StreamReader");
            t.setDaemon(true);
            return t;
        });
    }

    private String resolvePythonCommand(String configuredCmd) {
        List<String> candidates = new ArrayList<>();
        if (configuredCmd != null && !configuredCmd.isBlank()) {
            candidates.add(configuredCmd.trim());
        }
        candidates.add("py");
        candidates.add("python");
        candidates.add("python3");

        for (String cmd : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    return cmd;
                }
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        return (configuredCmd != null && !configuredCmd.isBlank()) ? configuredCmd : "python";
    }

    public CrawlResponse crawl(String targetUrl, String targetType, String lastHash) {
        List<String> command = new ArrayList<>();
        command.add(pythonExecutable);
        command.add(scriptPath);
        command.add("--url");
        command.add(targetUrl);
        command.add("--type");
        command.add((targetType != null && !targetType.isBlank()) ? targetType : "auto");

        if (lastHash != null && !lastHash.isBlank()) {
            command.add("--last-hash");
            command.add(lastHash);
        }

        if (allowLocalMock) {
            command.add("--allow-local-mock");
        }

        if (!accuweatherApiKey.isBlank()) {
            command.add("--accu-api-key");
            command.add(accuweatherApiKey);
        }

        if (!accuweatherLocationKey.isBlank()) {
            command.add("--accu-location-key");
            command.add(accuweatherLocationKey);
        }

        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
            processBuilder.directory(new File("."));

            process = processBuilder.start();

            // Read standard output and standard error CONCURRENTLY to prevent OS pipe buffer deadlock
            final Process procRef = process;
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStreamFully(procRef.getInputStream()), streamPool);
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStreamFully(procRef.getErrorStream()), streamPool);

            // Wait for process to exit with a hard timeout
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                // Kill process forcibly on timeout to prevent hanging or orphaned processes
                process.destroyForcibly();
                stdoutFuture.cancel(true);
                stderrFuture.cancel(true);

                CrawlResponse timeoutResponse = new CrawlResponse();
                timeoutResponse.setSuccess(false);
                timeoutResponse.setChanged(false);
                timeoutResponse.setUrl(targetUrl);
                timeoutResponse.setError(String.format(
                        "Process execution timed out after %d seconds. Process was forcibly terminated.",
                        timeoutSeconds));
                return timeoutResponse;
            }

            int exitCode = process.exitValue();
            String stdout = "";
            String stderr = "";

            try {
                stdout = stdoutFuture.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                stdoutFuture.cancel(true);
            }

            try {
                stderr = stderrFuture.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                stderrFuture.cancel(true);
            }

            String jsonOutput = stdout.trim();

            if (!jsonOutput.isEmpty()) {
                // Jackson parses structured JSON from crawler
                try {
                    return objectMapper.readValue(jsonOutput, CrawlResponse.class);
                } catch (Exception jsonEx) {
                    CrawlResponse parseErr = new CrawlResponse();
                    parseErr.setSuccess(false);
                    parseErr.setChanged(false);
                    parseErr.setUrl(targetUrl);
                    parseErr.setError("JSON parse error: " + jsonEx.getMessage() + ". Raw stdout: " + jsonOutput);
                    return parseErr;
                }
            }

            CrawlResponse errResponse = new CrawlResponse();
            errResponse.setSuccess(false);
            errResponse.setChanged(false);
            errResponse.setUrl(targetUrl);
            errResponse.setError("Process exited with code " + exitCode + ": " + stderr.trim());
            return errResponse;

        } catch (Exception e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            CrawlResponse errResponse = new CrawlResponse();
            errResponse.setSuccess(false);
            errResponse.setChanged(false);
            errResponse.setUrl(targetUrl);
            errResponse.setError("Bridge execution failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return errResponse;
        }
    }

    private String readStreamFully(InputStream stream) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    public String formatPrettyJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            return String.valueOf(object);
        }
    }

    public String getPythonExecutable() {
        return pythonExecutable;
    }

    public String getScriptPath() {
        return scriptPath;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
