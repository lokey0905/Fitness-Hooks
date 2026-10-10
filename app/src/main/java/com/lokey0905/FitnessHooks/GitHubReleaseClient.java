package com.lokey0905.FitnessHooks;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

public final class GitHubReleaseClient {
    public static final String RELEASES_URL = "https://github.com/lokey0905/Fitness-Hooks/releases";
    private static final String API_URL =
            "https://api.github.com/repos/lokey0905/Fitness-Hooks/releases/latest";
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    static final int MAX_NOTES_LENGTH = 8000;
    private static final int TIMEOUT_MS = 10_000;
    private final Transport transport;

    public enum State {
        UPDATE_AVAILABLE, UP_TO_DATE, NO_RELEASE, RATE_LIMITED, FAILED
    }

    public static final class Release {
        public final String version;
        public final String notes;
        public final String url;

        private Release(String version, String notes, String url) {
            this.version = version;
            this.notes = notes;
            this.url = url;
        }
    }

    public static final class Result {
        public final State state;
        public final Release release;

        private Result(State state, Release release) {
            this.state = state;
            this.release = release;
        }
    }

    interface Transport {
        Response get() throws IOException;
    }

    static final class Response {
        final int status;
        final String body;
        final boolean rateLimited;

        Response(int status, String body, boolean rateLimited) {
            this.status = status;
            this.body = body;
            this.rateLimited = rateLimited;
        }
    }

    public GitHubReleaseClient() {
        this(GitHubReleaseClient::fetchLatest);
    }

    GitHubReleaseClient(Transport transport) {
        this.transport = transport;
    }

    public Result check(String installedVersion) {
        if (normalizeVersion(installedVersion) == null || Thread.currentThread().isInterrupted()) {
            return new Result(State.FAILED, null);
        }
        try {
            return parseResponse(transport.get(), installedVersion);
        } catch (IOException | RuntimeException exception) {
            return new Result(State.FAILED, null);
        }
    }

    static Result parseResponse(Response response, String installedVersion) {
        if (response.status == HttpURLConnection.HTTP_NOT_FOUND) {
            return new Result(State.NO_RELEASE, null);
        }
        if (response.status == 429
                || (response.status == HttpURLConnection.HTTP_FORBIDDEN && response.rateLimited)) {
            return new Result(State.RATE_LIMITED, null);
        }
        if (response.status != HttpURLConnection.HTTP_OK || response.body == null
                || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            return new Result(State.FAILED, null);
        }
        try {
            JSONObject json = new JSONObject(response.body);
            if (!(json.opt("draft") instanceof Boolean)
                    || !(json.opt("prerelease") instanceof Boolean)) {
                return new Result(State.FAILED, null);
            }
            if (json.getBoolean("draft") || json.getBoolean("prerelease")) {
                return new Result(State.NO_RELEASE, null);
            }
            if (!(json.opt("tag_name") instanceof String)) {
                return new Result(State.FAILED, null);
            }
            String latest = normalizeVersion(json.getString("tag_name"));
            String installed = normalizeVersion(installedVersion);
            if (latest == null || installed == null) {
                return new Result(State.FAILED, null);
            }
            Object body = json.opt("body");
            String notes = body instanceof String ? (String) body : "";
            if (notes.length() > MAX_NOTES_LENGTH) {
                int end = MAX_NOTES_LENGTH - 3;
                if (Character.isHighSurrogate(notes.charAt(end - 1))) {
                    end--;
                }
                notes = notes.substring(0, end) + "...";
            }
            Release release = new Release(latest, notes,
                    validateReleaseUrl(json.optString("html_url", "")));
            return new Result(compareVersions(latest, installed) > 0
                    ? State.UPDATE_AVAILABLE : State.UP_TO_DATE, release);
        } catch (JSONException | IllegalArgumentException exception) {
            return new Result(State.FAILED, null);
        }
    }

    static String normalizeVersion(String version) {
        if (version == null || version.length() > 128
                || !version.matches("[vV]?[0-9]+(?:\\.[0-9]+){0,7}")) {
            return null;
        }
        return version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
    }

    static int compareVersions(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            BigInteger a = index < left.length ? new BigInteger(left[index]) : BigInteger.ZERO;
            BigInteger b = index < right.length ? new BigInteger(right[index]) : BigInteger.ZERO;
            int result = a.compareTo(b);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    static String validateReleaseUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getRawPath();
            String prefix = "/lokey0905/Fitness-Hooks/releases/tag/";
            if ("https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && path != null && path.startsWith(prefix) && path.length() > prefix.length()
                    && normalizeVersion(path.substring(prefix.length())) != null) {
                return uri.toASCIIString();
            }
        } catch (URISyntaxException ignored) {
            // Untrusted release metadata must never change the destination repository.
        }
        return RELEASES_URL;
    }

    private static Response fetchLatest() throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(API_URL).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
            connection.setRequestProperty("User-Agent", "Fitness-Hooks-Update-Check");
            int status = connection.getResponseCode();
            boolean rateLimited = "0".equals(connection.getHeaderField("X-RateLimit-Remaining"))
                    || connection.getHeaderField("Retry-After") != null;
            if (status != HttpURLConnection.HTTP_OK) {
                return new Response(status, null, rateLimited);
            }
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
                throw new IOException("Release response too large");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Update check interrupted");
                    }
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("Release response too large");
                    }
                    output.write(buffer, 0, count);
                }
                return new Response(status, output.toString(StandardCharsets.UTF_8.name()), false);
            }
        } finally {
            connection.disconnect();
        }
    }
}
