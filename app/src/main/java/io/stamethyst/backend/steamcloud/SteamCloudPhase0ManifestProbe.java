package io.stamethyst.backend.steamcloud;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.discovery.ServerRecord;
import in.dragonbra.javasteam.steam.discovery.SmartCMServerList;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;
import in.dragonbra.javasteam.util.log.LogListener;
import in.dragonbra.javasteam.util.log.LogManager;
import io.stamethyst.config.RuntimePaths;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class SteamCloudPhase0ManifestProbe {
    private static final String TAG = "SteamCloudPhase0";
    private static final int APP_ID = 646570;
    private static final long CONNECT_TIMEOUT_MS = 40_000L;
    private static final long LOGON_TIMEOUT_MS = 45_000L;
    private static final long RPC_TIMEOUT_MS = 60_000L;
    private static final long HTTP_TIMEOUT_MS = 60_000L;
    private static final long CALLBACK_POLL_TIMEOUT_MS = 250L;
    private static final String OUTPUT_DIR_NAME = "steam-cloud-phase0";
    private static final String LISTING_FILE_NAME = "cloud-list.tsv";
    private static final String SUMMARY_FILE_NAME = "summary.txt";
    private static final String LAST_CM_ENDPOINT_FILE_NAME = "last-websocket-cm-endpoint.txt";
    private static final int JAVA_STEAM_LOG_TAIL_LIMIT = 12;
    private static final int JAVA_STEAM_STACKTRACE_LINE_LIMIT = 24;
    private static final String PREFERENCES_PREFIX = "%GameInstall%preferences/";
    private static final String SAVES_PREFIX = "%GameInstall%saves/";

    private SteamCloudPhase0ManifestProbe() {}

    public static Result run(
        Context context,
        String accountName,
        String refreshToken,
        String proxyUrl
    ) throws Exception {
        return run(context, accountName, refreshToken, proxyUrl, true);
    }

    private static Result run(
        Context context,
        String accountName,
        String refreshToken,
        String proxyUrl,
        boolean allowReconnectRetry
    ) throws Exception {
        File outputDir = new File(RuntimePaths.storageRoot(context), OUTPUT_DIR_NAME);
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IOException("Failed to create Phase 0 output directory: " + outputDir.getAbsolutePath());
        }

        File listingFile = new File(outputDir, LISTING_FILE_NAME);
        File summaryFile = new File(outputDir, SUMMARY_FILE_NAME);
        File lastCmEndpointFile = new File(outputDir, LAST_CM_ENDPOINT_FILE_NAME);
        long startedAtMs = System.currentTimeMillis();
        String stage = "Resolve proxy settings";
        ProxySettings proxySettings = null;
        Runner runner = null;
        try {
            proxySettings = ProxySettings.parse(proxyUrl);
            stage = "Create Steam client";
            runner = new Runner(context, proxySettings, lastCmEndpointFile);
            stage = "Steam connect";
            runner.start();
            stage = "Steam logon";
            runner.logOn(accountName, refreshToken);
            stage = "GetAppFileChangelist";
            List<RemoteFileEntry> entries = runner.listFiles(APP_ID);
            long completedAtMs = System.currentTimeMillis();
            stage = "Write manifest listing";
            writeListing(listingFile, entries);
            int preferencesCount = 0;
            int savesCount = 0;
            for (RemoteFileEntry entry : entries) {
                if (entry.remotePath.startsWith(PREFERENCES_PREFIX)) {
                    preferencesCount++;
                } else if (entry.remotePath.startsWith(SAVES_PREFIX)) {
                    savesCount++;
                }
            }
            stage = "Write summary";
            writeSuccessSummary(
                summaryFile,
                accountName,
                startedAtMs,
                completedAtMs,
                proxySettings,
                runner,
                entries.size(),
                preferencesCount,
                savesCount,
                listingFile
            );
            Log.i(
                TAG,
                "Phase 0 manifest probe completed: files="
                    + entries.size()
                    + ", preferences="
                    + preferencesCount
                    + ", saves="
                    + savesCount
                    + ", output="
                    + listingFile.getAbsolutePath()
            );
            return new Result(
                entries.size(),
                preferencesCount,
                savesCount,
                listingFile,
                summaryFile,
                completedAtMs
            );
        } catch (Throwable error) {
            if (allowReconnectRetry && isManifestReconnectRetryCandidate(error, stage, runner)) {
                SteamCloudNetworkEnvironment.INSTANCE.clearNetworkCache(context);
                if (runner != null) {
                    runner.close();
                    runner = null;
                }
                return run(context, accountName, refreshToken, proxyUrl, false);
            }
            long completedAtMs = System.currentTimeMillis();
            String effectiveStage = stage;
            String summary = buildUserFailureSummary(effectiveStage, proxySettings, runner, error);
            File writtenSummaryFile = null;
            try {
                writeFailureSummary(
                    summaryFile,
                    accountName,
                    startedAtMs,
                    completedAtMs,
                    proxySettings,
                    runner,
                    effectiveStage,
                    error
                );
                writtenSummaryFile = summaryFile;
            } catch (Throwable summaryError) {
                Log.w(TAG, "Failed to write Phase 0 failure summary.", summaryError);
            }
            throw new ProbeFailureException(summary, writtenSummaryFile, error);
        } finally {
            if (runner != null) {
                runner.close();
            }
        }
    }

    private static void writeListing(File target, List<RemoteFileEntry> entries) throws IOException {
        List<String> lines = new ArrayList<>(entries.size() + 1);
        lines.add("appId\tindex\tremotePath\tfilename\tpathPrefix\tmachineName\trawFileSize\ttimestamp\tpersistState");
        for (RemoteFileEntry entry : entries) {
            lines.add(
                escapeTsv(Integer.toString(APP_ID))
                    + "\t"
                    + escapeTsv(Integer.toString(entry.index))
                    + "\t"
                    + escapeTsv(entry.remotePath)
                    + "\t"
                    + escapeTsv(entry.filename)
                    + "\t"
                    + escapeTsv(entry.pathPrefix)
                    + "\t"
                    + escapeTsv(isBlank(entry.machineName) ? "<unknown>" : entry.machineName)
                    + "\t"
                    + escapeTsv(Long.toString(entry.rawFileSize))
                    + "\t"
                    + escapeTsv(formatTimestamp(entry.timestamp.toEpochMilli()))
                    + "\t"
                    + escapeTsv(entry.persistState)
            );
        }
        writeTextFile(target, String.join("\n", lines) + "\n");
    }

    private static void writeSuccessSummary(
        File summaryFile,
        String accountName,
        long startedAtMs,
        long completedAtMs,
        ProxySettings proxySettings,
        Runner runner,
        int fileCount,
        int preferencesCount,
        int savesCount,
        File listingFile
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Steam Cloud Phase 0 manifest probe");
        lines.add("");
        lines.add("Outcome: SUCCESS");
        appendProbeContextLines(lines, accountName, startedAtMs, completedAtMs, proxySettings, runner, "Completed");
        lines.add("Total Files: " + fileCount);
        lines.add("preferences/: " + preferencesCount);
        lines.add("saves/: " + savesCount);
        lines.add("Summary: " + summaryFile.getAbsolutePath());
        lines.add("Listing: " + listingFile.getAbsolutePath());
        writeTextFile(summaryFile, String.join("\n", lines) + "\n");
    }

    private static void writeFailureSummary(
        File summaryFile,
        String accountName,
        long startedAtMs,
        long completedAtMs,
        ProxySettings proxySettings,
        Runner runner,
        String stage,
        Throwable error
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Steam Cloud Phase 0 manifest probe");
        lines.add("");
        lines.add("Outcome: FAILED");
        appendProbeContextLines(lines, accountName, startedAtMs, completedAtMs, proxySettings, runner, stage);
        lines.add("Failure Summary: " + buildUserFailureSummary(stage, proxySettings, runner, error));
        lines.add("Summary: " + summaryFile.getAbsolutePath());
        lines.add("Listing: <not written>");
        appendExceptionChain(lines, error);
        if (runner != null) {
            runner.appendJavaSteamDiagnostics(lines);
        }
        writeTextFile(summaryFile, String.join("\n", lines) + "\n");
    }

    private static void writeTextFile(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory for " + target.getAbsolutePath());
        }
        File tempFile = new File(parent, "." + target.getName() + "." + System.nanoTime() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tempFile)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to replace existing file: " + target.getAbsolutePath());
        }
        if (!tempFile.renameTo(target)) {
            throw new IOException("Failed to move temp file into place: " + target.getAbsolutePath());
        }
    }

    private static String formatTimestamp(long timestampMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timestampMs));
    }

    private static void appendProbeContextLines(
        List<String> lines,
        String accountName,
        long startedAtMs,
        long completedAtMs,
        ProxySettings proxySettings,
        Runner runner,
        String stage
    ) {
        lines.add("Account: " + accountName);
        lines.add("App ID: " + APP_ID);
        lines.add("Started At: " + formatTimestamp(startedAtMs));
        lines.add("Completed At: " + formatTimestamp(completedAtMs));
        lines.add("Duration Ms: " + Math.max(0L, completedAtMs - startedAtMs));
        lines.add("Transport Mode: " + describeTransportMode(proxySettings));
        lines.add("Configured Proxy: " + describeConfiguredProxy(proxySettings));
        lines.add(
            "Protocol Types: "
                + (runner == null
                    ? describeProtocolTypes(resolveProtocolTypes(proxySettings))
                    : runner.getProtocolTypesDescription())
        );
        lines.add("Current Stage: " + stage);
        lines.add(
            "Connected Callback: "
                + (runner != null && runner.hasConnectedCallback() ? "received" : "not received")
        );
        lines.add(
            "Logon Result: "
                + (runner == null ? "<not received>" : runner.getLoggedOnResultDescription())
        );
        lines.add(
            "Disconnected Callback: "
                + (runner == null ? "<not observed>" : runner.getDisconnectedDescription())
        );
        lines.add(
            "Resolved CM Endpoint: "
                + (runner == null ? "<not resolved>" : runner.getResolvedServerDescription())
        );
        lines.add(
            "CM Candidate Source: "
                + (runner == null ? "<not selected>" : runner.getCandidateSourceDescription())
        );
        lines.add(
            "WSS Preflight: "
                + (runner == null ? "<not run>" : runner.getWebSocketPreflightDescription())
        );
        lines.add(
            "JavaSteam Last Log: "
                + (runner == null ? "<not captured>" : runner.getJavaSteamLastLogDescription())
        );
        lines.add(
            "JavaSteam Last Error: "
                + (runner == null ? "<not captured>" : runner.getJavaSteamLastErrorDescription())
        );
    }

    private static void appendExceptionChain(List<String> lines, Throwable error) {
        lines.add("Exception Chain:");
        Throwable current = unwrapAsyncThrowable(error);
        int depth = 0;
        while (current != null && depth < 8) {
            String message = current.getMessage();
            if (isBlank(message)) {
                message = "<no message>";
            }
            lines.add("  - " + current.getClass().getName() + ": " + message);
            Throwable next = current.getCause();
            if (next == null || next == current) {
                break;
            }
            current = next;
            depth++;
        }
    }

    private static String buildUserFailureSummary(
        String stage,
        ProxySettings proxySettings,
        Runner runner,
        Throwable error
    ) {
        Throwable root = unwrapAsyncThrowable(error);
        String detail = root.getMessage();
        if (isBlank(detail)) {
            detail = root.getClass().getSimpleName();
        }
        String protocolDescription = runner == null
            ? describeProtocolTypes(resolveProtocolTypes(proxySettings))
            : runner.getProtocolTypesDescription();
        return stage
            + " failed [mode="
            + describeTransportMode(proxySettings)
            + ", protocol="
            + protocolDescription
            + "]: "
            + detail;
    }

    private static String describeTransportMode(ProxySettings proxySettings) {
        return proxySettings == null ? "direct" : "explicit-proxy";
    }

    private static String describeConfiguredProxy(ProxySettings proxySettings) {
        return proxySettings == null ? "none" : proxySettings.describe();
    }

    private static Throwable unwrapAsyncThrowable(Throwable error) {
        Throwable current = error;
        while (current instanceof ExecutionException && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String escapeTsv(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String sanitizeSingleLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String describeThrowable(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = sanitizeSingleLine(error.getMessage());
        if (message.isEmpty()) {
            return error.getClass().getName();
        }
        return error.getClass().getName() + ": " + message;
    }

    private static List<String> buildStackTraceLines(Throwable error, int limit) {
        List<String> lines = new ArrayList<>();
        if (error == null || limit <= 0) {
            return lines;
        }
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        error.printStackTrace(printWriter);
        printWriter.flush();
        for (String line : stringWriter.toString().split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            lines.add(trimmed);
            if (lines.size() >= limit) {
                break;
            }
        }
        return lines;
    }

    private static String readOptionalTextFile(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            Log.w(TAG, "Failed to read text file: " + file.getAbsolutePath(), error);
            return "";
        }
    }

    private static void applyProxySystemProperties(ProxySettings proxySettings) {
        System.setProperty("java.net.useSystemProxies", "true");

        clearProxySystemProperty("http.proxyHost");
        clearProxySystemProperty("http.proxyPort");
        clearProxySystemProperty("https.proxyHost");
        clearProxySystemProperty("https.proxyPort");
        clearProxySystemProperty("socksProxyHost");
        clearProxySystemProperty("socksProxyPort");

        if (proxySettings == null) {
            return;
        }

        String host = proxySettings.uri.getHost();
        String port = Integer.toString(proxySettings.uri.getPort());
        if (proxySettings.proxy.type() == Proxy.Type.SOCKS) {
            System.setProperty("socksProxyHost", host);
            System.setProperty("socksProxyPort", port);
            return;
        }

        System.setProperty("http.proxyHost", host);
        System.setProperty("http.proxyPort", port);
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
    }

    private static void clearProxySystemProperty(String key) {
        if (System.getProperty(key) != null) {
            System.clearProperty(key);
        }
    }

    private static String joinRemotePath(String prefix, String filename) {
        if (isBlank(prefix)) {
            return filename;
        }
        if (isBlank(filename)) {
            return prefix;
        }
        char separator = prefix.indexOf('\\') >= 0 && prefix.indexOf('/') < 0 ? '\\' : '/';
        if (prefix.endsWith("/") || prefix.endsWith("\\")) {
            return prefix + filename;
        }
        return prefix + separator + filename;
    }

    private static boolean isManifestReconnectRetryCandidate(
        Throwable error,
        String stage,
        Runner runner
    ) {
        boolean sawManifestStage = sanitizeSingleLine(stage).toLowerCase(Locale.US).contains("getappfilechangelist");
        boolean sawReconnectFailure = runner != null
            && sanitizeSingleLine(runner.getDisconnectedDescription()).toLowerCase(Locale.US).contains("unexpected");
        Throwable current = error;
        while (current != null) {
            String normalized = sanitizeSingleLine(current.getMessage()).toLowerCase(Locale.US);
            if (normalized.contains("getappfilechangelist")) {
                sawManifestStage = true;
            }
            if ((normalized.contains("steam disconnected") && normalized.contains("unexpected"))
                || normalized.contains("client or session is no longer active")) {
                sawReconnectFailure = true;
            }
            current = current.getCause() != current ? current.getCause() : null;
        }
        return sawManifestStage && sawReconnectFailure;
    }

    @SuppressWarnings("unchecked")
    private static <T> T waitForEither(
        CompletableFuture<T> future,
        CompletableFuture<?> abortFuture,
        long timeoutMs,
        String stage
    ) throws Exception {
        CompletableFuture<Object> combined = new CompletableFuture<>();
        future.whenComplete((value, error) -> {
            if (error != null) {
                combined.completeExceptionally(error);
                return;
            }
            combined.complete(value);
        });
        if (abortFuture != null) {
            abortFuture.whenComplete((value, error) -> {
                if (error != null) {
                    combined.completeExceptionally(error);
                    return;
                }
                combined.completeExceptionally(new IllegalStateException(stage + " was aborted before completion."));
            });
        }

        try {
            return (T) combined.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            Throwable cause = unwrapAsyncThrowable(error);
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(stage + " failed.", cause);
        } catch (TimeoutException error) {
            throw new TimeoutException(stage + " timed out after " + (timeoutMs / 1000L) + "s.");
        }
    }

    private static EnumSet<ProtocolTypes> resolveProtocolTypes(ProxySettings proxySettings) {
        // Android Phase 0 always uses websocket only so the app avoids Steam CM TCP ports
        // that are less reliable under mobile proxy and transparent proxy environments.
        return EnumSet.of(ProtocolTypes.WEB_SOCKET);
    }

    private static String describeProtocolTypes(EnumSet<ProtocolTypes> protocolTypes) {
        List<String> values = new ArrayList<>(protocolTypes.size());
        for (ProtocolTypes protocolType : protocolTypes) {
            values.add(protocolType.name());
        }
        values.sort(String::compareTo);
        return String.join(", ", values);
    }

    public static final class Result {
        private final int fileCount;
        private final int preferencesCount;
        private final int savesCount;
        private final File listingFile;
        private final File summaryFile;
        private final long completedAtMs;

        private Result(
            int fileCount,
            int preferencesCount,
            int savesCount,
            File listingFile,
            File summaryFile,
            long completedAtMs
        ) {
            this.fileCount = fileCount;
            this.preferencesCount = preferencesCount;
            this.savesCount = savesCount;
            this.listingFile = listingFile;
            this.summaryFile = summaryFile;
            this.completedAtMs = completedAtMs;
        }

        public int getFileCount() {
            return fileCount;
        }

        public int getPreferencesCount() {
            return preferencesCount;
        }

        public int getSavesCount() {
            return savesCount;
        }

        public File getListingFile() {
            return listingFile;
        }

        public File getSummaryFile() {
            return summaryFile;
        }

        public long getCompletedAtMs() {
            return completedAtMs;
        }
    }

    public static final class ProbeFailureException extends Exception {
        private final File summaryFile;

        private ProbeFailureException(String message, File summaryFile, Throwable cause) {
            super(message, cause);
            this.summaryFile = summaryFile;
        }

        public File getSummaryFile() {
            return summaryFile;
        }
    }

    private static final class RemoteFileEntry {
        private final int index;
        private final String remotePath;
        private final String normalizedPath;
        private final String filename;
        private final String pathPrefix;
        private final String machineName;
        private final long rawFileSize;
        private final Instant timestamp;
        private final String persistState;

        private RemoteFileEntry(
            int index,
            String remotePath,
            String normalizedPath,
            String filename,
            String pathPrefix,
            String machineName,
            long rawFileSize,
            Instant timestamp,
            String persistState
        ) {
            this.index = index;
            this.remotePath = remotePath;
            this.normalizedPath = normalizedPath;
            this.filename = filename;
            this.pathPrefix = pathPrefix;
            this.machineName = machineName;
            this.rawFileSize = rawFileSize;
            this.timestamp = timestamp;
            this.persistState = persistState;
        }

        private RemoteFileEntry withIndex(int nextIndex) {
            return new RemoteFileEntry(
                nextIndex,
                remotePath,
                normalizedPath,
                filename,
                pathPrefix,
                machineName,
                rawFileSize,
                timestamp,
                persistState
            );
        }
    }

    private static final class ProxySettings {
        private final URI uri;
        private final Proxy proxy;

        private ProxySettings(URI uri, Proxy proxy) {
            this.uri = uri;
            this.proxy = proxy;
        }

        private static ProxySettings parse(String rawUrl) {
            if (isBlank(rawUrl)) {
                return null;
            }

            URI uri;
            try {
                uri = URI.create(rawUrl.trim());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid proxy URL: " + rawUrl, error);
            }

            String scheme = uri.getScheme() == null ? "" : uri.getScheme().trim().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            int port = uri.getPort();
            if (isBlank(host) || port <= 0) {
                throw new IllegalArgumentException("Proxy URL must include a host and port: " + rawUrl);
            }

            Proxy.Type proxyType;
            if ("http".equals(scheme) || "https".equals(scheme)) {
                proxyType = Proxy.Type.HTTP;
            } else if ("socks".equals(scheme) || "socks5".equals(scheme)) {
                proxyType = Proxy.Type.SOCKS;
            } else {
                throw new IllegalArgumentException(
                    "Unsupported proxy scheme '" + scheme + "'. Use http:// or socks5://."
                );
            }

            return new ProxySettings(uri, new Proxy(proxyType, new InetSocketAddress(host, port)));
        }

        private String describe() {
            return proxy.type().name().toLowerCase(Locale.ROOT) + "://" + uri.getHost() + ":" + uri.getPort();
        }
    }

    private static final class Runner implements AutoCloseable {
        private final OkHttpClient httpClient;
        private final SteamConfiguration steamConfiguration;
        private final SteamClient steamClient;
        private final CallbackManager callbackManager;
        private final SteamUser steamUser;
        private final SteamCloud steamCloud;
        private final SteamCloudProtocolClient protocolClient;
        private final ProxySettings proxySettings;
        private final File lastCmEndpointFile;
        private final EnumSet<ProtocolTypes> protocolTypes;
        private final JavaSteamLogCollector javaSteamLogCollector;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
        private final CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
        private final CompletableFuture<Void> disconnectedFuture = new CompletableFuture<>();
        private final CompletableFuture<LoggedOnCallback> loggedOnFuture = new CompletableFuture<>();
        private final CompletableFuture<LoggedOffCallback> loggedOffFuture = new CompletableFuture<>();
        private volatile String currentStage = "startup";
        private volatile boolean connectedCallbackReceived = false;
        private volatile EResult loggedOnResult = null;
        private volatile String disconnectedDescription = "<not observed>";
        private volatile String resolvedServerDescription = "<not resolved>";
        private volatile String candidateSourceDescription = "<not selected>";
        private volatile String webSocketPreflightDescription = "<not run>";
        private Thread callbackThread;

        private Runner(Context context, ProxySettings proxySettings, File lastCmEndpointFile) {
            this.proxySettings = proxySettings;
            this.lastCmEndpointFile = lastCmEndpointFile;
            this.protocolTypes = resolveProtocolTypes(proxySettings);
            applyProxySystemProperties(proxySettings);

            OkHttpClient.Builder httpClientBuilder = SteamCloudAcceleratedHttp.createClient(
                context,
                HTTP_TIMEOUT_MS,
                HTTP_TIMEOUT_MS,
                HTTP_TIMEOUT_MS
            ).newBuilder();
            if (proxySettings != null) {
                httpClientBuilder.proxy(proxySettings.proxy);
            }
            httpClient = httpClientBuilder.build();
            protocolClient = new SteamCloudProtocolClient(
                httpClient,
                SteamCloudAcceleratedHttp.createWebSocketFactory(context, httpClient)
            );
            steamConfiguration = null;
            steamClient = null;
            callbackManager = null;
            steamUser = null;
            steamCloud = null;
            javaSteamLogCollector = null;
        }

        private void start() throws Exception {
            running.set(true);
            Log.i(
                TAG,
                "Preparing accelerated Steam CM transport for Phase 0 manifest probe. protocol="
                    + "OkHttp websocket"
                    + ", proxy="
                    + (proxySettings == null ? "none" : proxySettings.describe())
            );
            connectedCallbackReceived = true;
            candidateSourceDescription = "Steam directory via accelerated OkHttp";
            resolvedServerDescription = "Steam directory websocket CM";
            webSocketPreflightDescription = "not separately run (protocol session validates CM connection)";
        }

        private void logOn(String accountName, String refreshToken) throws Exception {
            currentStage = "Steam logon";
            protocolClient.logOn(accountName, refreshToken, "");
            loggedOnResult = EResult.OK;
        }

        private List<RemoteFileEntry> listFiles(int appId) throws Exception {
            currentStage = "GetAppFileChangelist";
            in.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient.CCloud_GetAppFileChangelist_Response changeList =
                protocolClient.getAppFileChangelist(appId);

            List<RemoteFileEntry> entries = new ArrayList<>();
            for (in.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient.CCloud_AppFileInfo file : changeList.getFilesList()) {
                String pathPrefix = "";
                if (file.getPathPrefixIndex() < changeList.getPathPrefixesCount()) {
                    pathPrefix = changeList.getPathPrefixes(file.getPathPrefixIndex());
                }
                String machineName = "";
                if (file.getMachineNameIndex() < changeList.getMachineNamesCount()) {
                    machineName = changeList.getMachineNames(file.getMachineNameIndex());
                }
                String remotePath = joinRemotePath(pathPrefix, file.getFileName());
                entries.add(
                    new RemoteFileEntry(
                        -1,
                        remotePath,
                        remotePath.replace('\\', '/'),
                        file.getFileName(),
                        pathPrefix,
                        machineName,
                        file.getRawFileSize(),
                        Instant.ofEpochSecond(file.getTimeStamp()),
                        file.getPersistState().name()
                    )
                );
            }
            entries.sort(Comparator.comparing(entry -> entry.normalizedPath.toLowerCase(Locale.ROOT)));
            List<RemoteFileEntry> indexed = new ArrayList<>(entries.size());
            int index = 1;
            for (RemoteFileEntry entry : entries) {
                indexed.add(entry.withIndex(index));
                index++;
            }
            return indexed;
        }

        private <T> T waitForStage(CompletableFuture<T> future, long timeoutMs, String stage) throws Exception {
            currentStage = stage;
            return waitForEither(future, disconnectedFuture, timeoutMs, stage);
        }

        private boolean hasConnectedCallback() {
            return connectedCallbackReceived;
        }

        private String getLoggedOnResultDescription() {
            return loggedOnResult == null ? "<not received>" : loggedOnResult.name();
        }

        private String getDisconnectedDescription() {
            return disconnectedDescription;
        }

        private String getResolvedServerDescription() {
            return resolvedServerDescription;
        }

        private String getCandidateSourceDescription() {
            return candidateSourceDescription;
        }

        private String getWebSocketPreflightDescription() {
            return webSocketPreflightDescription;
        }

        private String getProtocolTypesDescription() {
            return describeProtocolTypes(protocolTypes);
        }

        private String getJavaSteamLastLogDescription() {
            return "<not applicable: accelerated protocol CM>";
        }

        private String getJavaSteamLastErrorDescription() {
            return "<not applicable: accelerated protocol CM>";
        }

        private ServerRecord selectWebSocketServerRecord() throws IOException {
            List<PreparedServerRecord> candidates = new ArrayList<>();

            ServerRecord serverListRecord = steamClient.getServers().getNextServerCandidate(protocolTypes);
            if (serverListRecord != null) {
                candidates.add(new PreparedServerRecord(serverListRecord, "Steam server list"));
            }

            addWebSocketAddressCandidate(candidates, readOptionalTextFile(lastCmEndpointFile), "Fallback cache");
            addWebSocketAddressCandidate(candidates, SmartCMServerList.getDefaultServerWebSocket(), "JavaSteam default websocket CM");

            IOException lastResolutionError = null;
            List<String> attemptedKeys = new ArrayList<>();
            for (PreparedServerRecord candidate : candidates) {
                String dedupeKey = buildServerRecordKey(candidate.serverRecord);
                if (attemptedKeys.contains(dedupeKey)) {
                    continue;
                }
                attemptedKeys.add(dedupeKey);
                try {
                    PreparedServerRecord prepared = materializeWebSocketServerRecord(candidate);
                    candidateSourceDescription = prepared.candidateSourceDescription;
                    return prepared.serverRecord;
                } catch (IOException error) {
                    lastResolutionError = error;
                    Log.w(
                        TAG,
                        "Skipping Phase 0 websocket CM candidate source="
                            + candidate.candidateSourceDescription
                            + " because endpoint pre-resolution failed: "
                            + sanitizeSingleLine(error.getMessage()),
                        error
                    );
                }
            }

            if (lastResolutionError != null) {
                throw lastResolutionError;
            }
            candidateSourceDescription = "Fallback unavailable";
            return null;
        }

        private void appendJavaSteamDiagnostics(List<String> lines) {
            lines.add("JavaSteam Diagnostics: <not applicable: accelerated protocol CM>");
        }

        private String describeServerRecord(ServerRecord serverRecord) {
            return serverRecord.getEndpoint().getHostString()
                + ":"
                + serverRecord.getEndpoint().getPort()
                + " ["
                + describeProtocolTypes(serverRecord.getProtocolTypes())
                + "]";
        }

        private PreparedServerRecord materializeWebSocketServerRecord(PreparedServerRecord candidate) throws IOException {
            ServerRecord serverRecord = candidate.serverRecord;
            if (serverRecord == null || serverRecord.getEndpoint() == null) {
                return candidate;
            }
            if (!serverRecord.getProtocolTypes().contains(ProtocolTypes.WEB_SOCKET)) {
                return candidate;
            }

            InetSocketAddress endpoint = serverRecord.getEndpoint();
            String host = sanitizeSingleLine(endpoint.getHostString());
            int port = endpoint.getPort();
            if (isBlank(host)) {
                return candidate;
            }

            InetAddress resolvedAddress = endpoint.getAddress();
            if (resolvedAddress != null) {
                String literalAddress = sanitizeSingleLine(resolvedAddress.getHostAddress());
                if (!isBlank(literalAddress)) {
                    if (isIpv6Literal(literalAddress)) {
                        String preferredAddress = !isIpLiteral(host)
                            ? SteamCloudClient.selectPreferredAddress(InetAddress.getAllByName(host))
                            : "";
                        if (!isBlank(preferredAddress)) {
                            return createWebSocketServerCandidate(
                                formatHostPort(preferredAddress, port),
                                candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + preferredAddress + ")"
                            );
                        }
                        throw new IOException(
                            "Skipping IPv6-only Phase 0 websocket CM endpoint because JavaSteam cannot parse IPv6 websocket literals on Android: "
                                + formatHostPort(literalAddress, port)
                        );
                    }
                    return createWebSocketServerCandidate(
                        formatHostPort(literalAddress, port),
                        candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + literalAddress + ")"
                    );
                }
                return candidate;
            }

            if (isIpLiteral(host)) {
                if (isIpv6Literal(host)) {
                    throw new IOException(
                        "Skipping IPv6 Phase 0 websocket CM endpoint because JavaSteam cannot parse IPv6 websocket literals on Android: "
                            + formatHostPort(host, port)
                    );
                }
                return candidate;
            }

            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses == null || addresses.length == 0) {
                throw new IOException("Failed to resolve Phase 0 websocket CM hostname: " + host);
            }
            String preferredAddress = SteamCloudClient.selectPreferredAddress(addresses);
            if (isBlank(preferredAddress)) {
                throw new IOException(
                    "Resolved Phase 0 websocket CM hostname had no IPv4 address usable by JavaSteam websocket parsing: " + host
                );
            }

            Log.i(TAG, "Pre-resolved Phase 0 websocket CM hostname " + host + " -> " + preferredAddress + '.');
            return createWebSocketServerCandidate(
                formatHostPort(preferredAddress, port),
                candidate.candidateSourceDescription + " (pre-resolved " + host + " -> " + preferredAddress + ")"
            );
        }

        private void addWebSocketAddressCandidate(
            List<PreparedServerRecord> candidates,
            String address,
            String source
        ) {
            if (isBlank(address)) {
                return;
            }
            try {
                candidates.add(createWebSocketServerCandidate(address, source));
            } catch (IOException error) {
                Log.w(
                    TAG,
                    "Skipping Phase 0 websocket CM candidate source="
                        + source
                        + ": "
                        + sanitizeSingleLine(error.getMessage()),
                    error
                );
            }
        }

        private PreparedServerRecord createWebSocketServerCandidate(
            String address,
            String source
        ) throws IOException {
            String normalizedAddress = sanitizeSingleLine(address);
            if (!SteamCloudClient.isWebSocketEndpointParserSafe(normalizedAddress)) {
                throw new IOException("Phase 0 websocket CM endpoint is not safe for JavaSteam parser: " + normalizedAddress);
            }
            try {
                return new PreparedServerRecord(
                    ServerRecord.createWebSocketServer(normalizedAddress),
                    source
                );
            } catch (RuntimeException error) {
                throw new IOException("Invalid Phase 0 websocket CM endpoint: " + normalizedAddress, error);
            }
        }

        private void persistResolvedWebSocketEndpoint(ServerRecord serverRecord) {
            if (serverRecord == null || !serverRecord.getProtocolTypes().contains(ProtocolTypes.WEB_SOCKET)) {
                return;
            }
            if (lastCmEndpointFile == null) {
                return;
            }
            String address = serverRecord.getEndpoint().getHostString();
            if (serverRecord.getEndpoint().getAddress() != null
                && !isBlank(serverRecord.getEndpoint().getAddress().getHostAddress())) {
                address = serverRecord.getEndpoint().getAddress().getHostAddress();
            }
            if (isIpv6Literal(address)) {
                Log.i(TAG, "Skipping Phase 0 websocket CM cache write for IPv6 literal endpoint: " + address);
                return;
            }
            address = formatHostPort(address, serverRecord.getEndpoint().getPort());
            try {
                writeTextFile(lastCmEndpointFile, address + "\n");
            } catch (IOException error) {
                Log.w(TAG, "Failed to persist websocket CM endpoint cache.", error);
            }
        }

        private String buildServerRecordKey(ServerRecord serverRecord) {
            if (serverRecord == null || serverRecord.getEndpoint() == null) {
                return "<null>";
            }
            return sanitizeSingleLine(serverRecord.getEndpoint().getHostString()).toLowerCase(Locale.ROOT)
                + ':'
                + serverRecord.getEndpoint().getPort()
                + '|'
                + describeProtocolTypes(serverRecord.getProtocolTypes());
        }

        private String formatHostPort(String host, int port) {
            String sanitizedHost = sanitizeSingleLine(host);
            if (sanitizedHost.indexOf(':') >= 0 && !sanitizedHost.startsWith("[") && !sanitizedHost.endsWith("]")) {
                sanitizedHost = '[' + sanitizedHost + ']';
            }
            return sanitizedHost + ':' + port;
        }

        private boolean isIpLiteral(String host) {
            String value = sanitizeSingleLine(host);
            if (value.isEmpty()) {
                return false;
            }
            if (value.indexOf(':') >= 0) {
                return true;
            }
            for (int index = 0; index < value.length(); index++) {
                char c = value.charAt(index);
                if ((c < '0' || c > '9') && c != '.') {
                    return false;
                }
            }
            return true;
        }

        private boolean isIpv6Literal(String host) {
            String value = sanitizeSingleLine(host);
            if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
                value = value.substring(1, value.length() - 1);
            }
            return value.indexOf(':') >= 0;
        }

        private String runWebSocketPreflight(ServerRecord serverRecord) {
            String url = "wss://" + serverRecord.getEndpoint().getHostString() + ":" + serverRecord.getEndpoint().getPort() + "/cmsocket/";
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> outcome = new AtomicReference<>("pending");
            OkHttpClient preflightClient = httpClient.newBuilder()
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .build();
            Request request = new Request.Builder()
                .url(url)
                .build();

            WebSocket webSocket = preflightClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    outcome.set(
                        "success: HTTP "
                            + response.code()
                            + (response.message() == null || response.message().isEmpty()
                                ? ""
                                : " " + response.message())
                    );
                    webSocket.close(1000, "phase0 preflight complete");
                    latch.countDown();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                    StringBuilder summary = new StringBuilder();
                    summary.append("failed: ");
                    if (response != null) {
                        summary.append("HTTP ").append(response.code());
                        if (response.message() != null && !response.message().isEmpty()) {
                            summary.append(' ').append(response.message());
                        }
                        summary.append(" | ");
                    }
                    summary.append(error.getClass().getSimpleName());
                    if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
                        summary.append(": ").append(error.getMessage().trim());
                    }
                    outcome.set(summary.toString());
                    latch.countDown();
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    if ("pending".equals(outcome.get())) {
                        outcome.set("closed: code=" + code + ", reason=" + reason);
                    }
                    latch.countDown();
                }
            });

            try {
                if (!latch.await(10L, TimeUnit.SECONDS)) {
                    outcome.compareAndSet("pending", "timed out after 10s");
                    webSocket.cancel();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                outcome.set("interrupted while waiting for preflight");
                webSocket.cancel();
            } finally {
                preflightClient.dispatcher().executorService().shutdown();
                preflightClient.connectionPool().evictAll();
            }
            return url + " -> " + outcome.get();
        }

        @Override
        public void close() {
            shuttingDown.set(true);
            running.set(false);
            protocolClient.close();
            applyProxySystemProperties(null);
        }

        private static final class PreparedServerRecord {
            private final ServerRecord serverRecord;
            private final String candidateSourceDescription;

            private PreparedServerRecord(ServerRecord serverRecord, String candidateSourceDescription) {
                this.serverRecord = serverRecord;
                this.candidateSourceDescription = candidateSourceDescription;
            }
        }
    }

    private static final class JavaSteamLogCollector implements LogListener {
        private static final int MAX_ENTRIES = 48;

        private final Object lock = new Object();
        private final ArrayDeque<JavaSteamLogEntry> entries = new ArrayDeque<>();
        private JavaSteamLogEntry lastErrorEntry;

        @Override
        public void onLog(Class<?> clazz, String message, Throwable error) {
            record("DEBUG", clazz, message, error);
        }

        @Override
        public void onError(Class<?> clazz, String message, Throwable error) {
            record("ERROR", clazz, message, error);
        }

        private void record(String level, Class<?> clazz, String message, Throwable error) {
            JavaSteamLogEntry entry = new JavaSteamLogEntry(
                level,
                clazz == null ? "<unknown>" : clazz.getName(),
                sanitizeSingleLine(message),
                error,
                buildStackTraceLines(error, JAVA_STEAM_STACKTRACE_LINE_LIMIT)
            );

            synchronized (lock) {
                while (entries.size() >= MAX_ENTRIES) {
                    entries.removeFirst();
                }
                entries.addLast(entry);
                if ("ERROR".equals(level) || error != null) {
                    lastErrorEntry = entry;
                }
            }

            if ("ERROR".equals(level) || error != null) {
                Log.e(TAG, "JavaSteam [" + level + "] " + entry.describe(), error);
            } else {
                Log.d(TAG, "JavaSteam [" + level + "] " + entry.describe());
            }
        }

        private String describeLastLog() {
            synchronized (lock) {
                return entries.isEmpty() ? "<none>" : entries.getLast().describe();
            }
        }

        private String describeLastError() {
            synchronized (lock) {
                return lastErrorEntry == null ? "<none>" : lastErrorEntry.describe();
            }
        }

        private void appendSummaryLines(List<String> lines) {
            List<JavaSteamLogEntry> snapshot;
            JavaSteamLogEntry errorSnapshot;
            synchronized (lock) {
                snapshot = new ArrayList<>(entries);
                errorSnapshot = lastErrorEntry;
            }

            if (snapshot.isEmpty()) {
                lines.add("JavaSteam Log Tail: <empty>");
                return;
            }

            lines.add("JavaSteam Log Tail:");
            int start = Math.max(0, snapshot.size() - JAVA_STEAM_LOG_TAIL_LIMIT);
            for (int index = start; index < snapshot.size(); index++) {
                lines.add("  - " + snapshot.get(index).describe());
            }

            if (errorSnapshot != null && !errorSnapshot.stackTraceLines.isEmpty()) {
                lines.add("JavaSteam Error Stack:");
                for (String line : errorSnapshot.stackTraceLines) {
                    lines.add("  " + line);
                }
            }
        }
    }

    private static final class JavaSteamLogEntry {
        private final String level;
        private final String sourceClass;
        private final String message;
        private final String throwableSummary;
        private final List<String> stackTraceLines;

        private JavaSteamLogEntry(
            String level,
            String sourceClass,
            String message,
            Throwable throwable,
            List<String> stackTraceLines
        ) {
            this.level = level;
            this.sourceClass = sourceClass;
            this.message = message == null ? "" : message;
            this.throwableSummary = describeThrowable(throwable);
            this.stackTraceLines = stackTraceLines;
        }

        private String describe() {
            StringBuilder builder = new StringBuilder();
            builder.append(level).append(' ').append(sourceClass);
            if (!message.isEmpty()) {
                builder.append(" - ").append(message);
            }
            if (!throwableSummary.isEmpty()) {
                builder.append(" | ").append(throwableSummary);
            }
            return builder.toString();
        }
    }
}
