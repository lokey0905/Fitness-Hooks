package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class GitHubReleaseClientTest {
    private static final String RELEASE_URL = GitHubReleaseClient.RELEASES_URL + "/tag/v2.3.0";

    @Test
    public void tagNameControlsUpdateNotReleaseTitle() throws Exception {
        JSONObject json = release("v2.3.0").put("name", "Fitness Hooks release 999");
        GitHubReleaseClient.Result result = parse(json.toString(), "2.2.0");
        assertEquals(GitHubReleaseClient.State.UPDATE_AVAILABLE, result.state);
        assertNotNull(result.release);
        assertEquals("2.3.0", result.release.version);
        assertEquals("Changes", result.release.notes);
        assertEquals(RELEASE_URL, result.release.url);
    }

    @Test
    public void comparesNumericComponentsInOrder() throws Exception {
        assertEquals(GitHubReleaseClient.State.UPDATE_AVAILABLE,
                parse(release("2.10.0").toString(), "2.9.99").state);
        assertEquals(GitHubReleaseClient.State.UP_TO_DATE,
                parse(release("1.99.99").toString(), "2.0.0").state);
        assertEquals(GitHubReleaseClient.State.UP_TO_DATE,
                parse(release("v2.2").toString(), "2.2.0").state);
        assertEquals(GitHubReleaseClient.State.UP_TO_DATE,
                parse(release("2.2.0").toString(), "2.3.0").state);
        assertTrue(GitHubReleaseClient.compareVersions("999999999999999999999", "3") > 0);
        assertEquals(0, GitHubReleaseClient.compareVersions("02.02.000", "2.2"));
    }

    @Test
    public void rejectsMalformedOrPrereleaseVersionTags() throws Exception {
        for (String tag : new String[]{"", "latest", "release-2.3.0", "2.3.0-beta", "2..3", " 2.3", "2.3 "}) {
            GitHubReleaseClient.Result result = parse(release(tag).toString(), "2.2.0");
            assertEquals(tag, GitHubReleaseClient.State.FAILED, result.state);
            assertNull(result.release);
        }
        assertEquals(GitHubReleaseClient.State.FAILED,
                parse(release("2.3.0").toString(), "invalid").state);
        assertNull(GitHubReleaseClient.normalizeVersion("1".repeat(129)));
        assertNull(GitHubReleaseClient.normalizeVersion("1.2.3.4.5.6.7.8.9"));
    }

    @Test
    public void ignoresDraftAndPrereleaseResults() throws Exception {
        assertEquals(GitHubReleaseClient.State.NO_RELEASE,
                parse(release("2.3.0").put("draft", true).toString(), "2.2.0").state);
        assertEquals(GitHubReleaseClient.State.NO_RELEASE,
                parse(release("2.3.0").put("prerelease", true).toString(), "2.2.0").state);
    }

    @Test
    public void validatesJsonShapeAndBoundsResponse() throws Exception {
        assertEquals(GitHubReleaseClient.State.FAILED, parse("not JSON", "2.2.0").state);
        assertEquals(GitHubReleaseClient.State.FAILED, parse("{}", "2.2.0").state);
        assertEquals(GitHubReleaseClient.State.FAILED,
                parse(release("2.3.0").put("draft", "false").toString(), "2.2.0").state);
        assertEquals(GitHubReleaseClient.State.FAILED,
                parse(release("2.3.0").put("tag_name", 3).toString(), "2.2.0").state);
        assertEquals(GitHubReleaseClient.State.FAILED,
                parse(release("2.3.0").put("body", "x".repeat(GitHubReleaseClient.MAX_RESPONSE_BYTES)).toString(), "2.2.0").state);
        assertEquals(GitHubReleaseClient.State.FAILED, parse(null, "2.2.0").state);
    }

    @Test
    public void toleratesMissingNotesAndCapsLongNotes() throws Exception {
        JSONObject noNotes = release("2.3.0");
        noNotes.remove("body");
        assertEquals("", parse(noNotes.toString(), "2.2.0").release.notes);
        assertEquals("", parse(release("2.3.0").put("body", JSONObject.NULL).toString(), "2.2.0").release.notes);
        GitHubReleaseClient.Result result = parse(release("2.3.0")
                .put("body", "a".repeat(GitHubReleaseClient.MAX_NOTES_LENGTH + 10)).toString(), "2.2.0");
        assertEquals(GitHubReleaseClient.MAX_NOTES_LENGTH, result.release.notes.length());
        assertTrue(result.release.notes.endsWith("..."));
    }

    @Test
    public void untrustedReleaseUrlsFallBackToFixedRepository() {
        for (String url : new String[]{
                "http://github.com/lokey0905/Fitness-Hooks/releases/tag/v2.3.0",
                "https://github.com.evil.example/lokey0905/Fitness-Hooks/releases/tag/v2.3.0",
                "https://github.com@evil.example/lokey0905/Fitness-Hooks/releases/tag/v2.3.0",
                "https://user@github.com/lokey0905/Fitness-Hooks/releases/tag/v2.3.0",
                "https://github.com:443/lokey0905/Fitness-Hooks/releases/tag/v2.3.0",
                "https://github.com/other/repo/releases/tag/v2.3.0",
                "https://github.com/lokey0905/Fitness-Hooks/releases/tag/v2.3.0?redirect=x",
                "https://github.com/lokey0905/Fitness-Hooks/releases/tag/../other",
                "https://github.com/lokey0905/Fitness-Hooks/releases/tag/%2e%2e%2fother",
                "https://github.com/lokey0905/Fitness-Hooks/releases/tag/v2.3.0#other",
                "javascript:alert(1)", "not a URL", ""}) {
            assertEquals(url, GitHubReleaseClient.RELEASES_URL, GitHubReleaseClient.validateReleaseUrl(url));
        }
        assertEquals(RELEASE_URL, GitHubReleaseClient.validateReleaseUrl(RELEASE_URL));
    }

    @Test
    public void mapsHttpFailuresWithoutClaimingUpToDate() {
        assertState(404, false, GitHubReleaseClient.State.NO_RELEASE);
        assertState(429, false, GitHubReleaseClient.State.RATE_LIMITED);
        assertState(403, true, GitHubReleaseClient.State.RATE_LIMITED);
        assertState(403, false, GitHubReleaseClient.State.FAILED);
        assertState(500, false, GitHubReleaseClient.State.FAILED);
        assertState(302, false, GitHubReleaseClient.State.FAILED);
    }

    @Test
    public void mapsTransportExceptionsToFailure() {
        for (IOException exception : new IOException[]{new IOException(), new SocketTimeoutException()}) {
            GitHubReleaseClient client = new GitHubReleaseClient(() -> { throw exception; });
            assertEquals(GitHubReleaseClient.State.FAILED, client.check("2.2.0").state);
        }
    }

    @Test
    public void invalidInstalledVersionDoesNotMakeNetworkRequest() {
        AtomicBoolean fetched = new AtomicBoolean();
        GitHubReleaseClient client = new GitHubReleaseClient(() -> {
            fetched.set(true);
            return new GitHubReleaseClient.Response(404, null, false);
        });
        assertEquals(GitHubReleaseClient.State.FAILED, client.check("invalid").state);
        assertFalse(fetched.get());
    }

    @Test
    public void successfulTransportUsesResponseParser() throws Exception {
        String body = release("v2.3.0").toString();
        GitHubReleaseClient client = new GitHubReleaseClient(
                () -> new GitHubReleaseClient.Response(200, body, false));
        assertEquals(GitHubReleaseClient.State.UPDATE_AVAILABLE, client.check("2.2.0").state);
    }

    private static JSONObject release(String tag) throws JSONException {
        return new JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
                .put("body", "Changes").put("html_url", RELEASE_URL);
    }

    private static GitHubReleaseClient.Result parse(String body, String installed) {
        return GitHubReleaseClient.parseResponse(new GitHubReleaseClient.Response(200, body, false), installed);
    }

    private static void assertState(int status, boolean limited, GitHubReleaseClient.State expected) {
        GitHubReleaseClient.Result result = GitHubReleaseClient.parseResponse(
                new GitHubReleaseClient.Response(status, null, limited), "2.2.0");
        assertEquals(expected, result.state);
        assertNull(result.release);
    }
}
