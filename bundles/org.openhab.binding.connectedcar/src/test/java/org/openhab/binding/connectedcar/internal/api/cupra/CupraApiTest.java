/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.connectedcar.internal.api.cupra;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone integration test for the CUPRA Born API.
 *
 * Uses java.net.http.HttpClient directly — no OSGi / openHAB framework required.
 * Mirrors the auth flow implemented in {@link BrandCupra} and {@code ApiWithOAuth}.
 *
 * <h2>Running</h2>
 *
 * <pre>
 *   # Reachability only (no credentials needed):
 *   mvn test -pl bundles/org.openhab.binding.connectedcar -Dtest=CupraApiTest#testOidcEndpointReachable
 *
 *   # Full auth + vehicle list:
 *   CUPRA_USER=me@example.com CUPRA_PASS=secret \
 *     mvn test -pl bundles/org.openhab.binding.connectedcar -Dtest=CupraApiTest#testFullAuthAndVehicleList
 * </pre>
 *
 * @author Andreas Gebauer - Initial contribution
 */
@Tag("integration")
class CupraApiTest {

    // ---- Cupra OIDC / API constants (mirrors BrandCupra static block) ----

    private static final String CLIENT_ID = "3c756d46-f1ba-4d78-9f9a-cff0d5292d51@apps_vw-dilab_com";
    private static final String CLIENT_SECRET = "eb8814e641c81a2640ad62eeccec11c98effc9bccd4269ab7af338b50a94b3a2";
    private static final String REDIRECT_URI = "cupra://oauth-callback";
    private static final String AUTH_BASE_URL = "https://identity.vwgroup.io";
    private static final String TOKEN_URL = AUTH_BASE_URL + "/oidc/v1/token";
    private static final String AUTHORIZE_URL = AUTH_BASE_URL + "/oidc/v1/authorize";
    private static final String API_BASE = "https://ola.prod.code.seat.cloud.vwgroup.com";
    private static final String SCOPE = "openid profile nickname birthdate phone";
    private static final String USER_AGENT = "CUPRAApp%20-%20Store/20220503 CFNetwork/1333.0.4 Darwin/21.5.0";

    // ------------------------------------------------------------------ //
    // Test 1 – Reachability (no credentials required) //
    // ------------------------------------------------------------------ //

    /**
     * Verifies that the CUPRA OIDC discovery endpoint is reachable and returns a
     * well-formed response. Passes without any Cupra account credentials.
     */
    @Test
    void testOidcEndpointReachable() throws Exception {
        HttpClient client = httpClient();

        HttpResponse<String> resp = client
                .send(HttpRequest.newBuilder().uri(URI.create(AUTH_BASE_URL + "/.well-known/openid-configuration"))
                        .header("User-Agent", USER_AGENT).GET().build(), BodyHandlers.ofString());

        System.out.println("[OIDC discovery] HTTP " + resp.statusCode());
        System.out.println("[OIDC discovery] " + resp.body().substring(0, Math.min(300, resp.body().length())));

        assertEquals(200, resp.statusCode(), "OIDC discovery endpoint should return 200");
        assertTrue(resp.body().contains("authorization_endpoint"),
                "Discovery document must contain authorization_endpoint");
    }

    // ------------------------------------------------------------------ //
    // Test 2 – Full auth flow + vehicle list (credentials required) //
    // ------------------------------------------------------------------ //

    /**
     * Performs the complete Cupra OIDC login flow:
     * <ol>
     * <li>GET authorize URL → VW login page</li>
     * <li>Parse HTML login form (csrf / relayState / hmac / action)</li>
     * <li>POST username → password form</li>
     * <li>POST password → {@code cupra://oauth-callback?code=…}</li>
     * <li>Exchange code for access + refresh token</li>
     * <li>GET {@code /vehicles} with bearer token</li>
     * </ol>
     *
     * Skipped unless the environment variables {@code CUPRA_USER} and {@code CUPRA_PASS}
     * are set.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "CUPRA_USER", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "CUPRA_PASS", matches = ".+")
    void testFullAuthAndVehicleList() throws Exception {
        String user = System.getenv("CUPRA_USER");
        assertNotNull(user);
        String pass = System.getenv("CUPRA_PASS");
        assertNotNull(pass);

        HttpClient client = httpClientWithCookies();

        // ---- Step 1: GET authorize URL, follow HTTP redirects to login form ----
        String nonce = "nonce" + System.currentTimeMillis();
        String state = "state" + System.currentTimeMillis();
        String authorizeUrl = AUTHORIZE_URL + "?response_type=" + enc("code id_token token") + "&client_id="
                + enc(CLIENT_ID) + "&redirect_uri=" + enc(REDIRECT_URI) + "&scope=" + enc(SCOPE) + "&nonce=" + nonce
                + "&state=" + state;

        HttpResponse<String> resp = followHttpRedirects(client, URI.create(authorizeUrl), get(client, authorizeUrl));
        System.out.println("[1] Login page: HTTP " + resp.statusCode() + " (" + resp.body().length() + " bytes)");
        assertEquals(200, resp.statusCode(), "Expected login form page");

        // ---- Step 2: Parse HTML login form fields ----
        String html = resp.body();
        String csrf = extractHiddenField(html, "_csrf");
        String relayState = extractHiddenField(html, "relayState");
        String hmac = extractHiddenField(html, "hmac");
        String action = extractFormAction(html);

        System.out.printf("[2] Form fields: csrf=%s… relayState=%s… hmac=%s…%n", crop(csrf, 8), crop(relayState, 8),
                crop(hmac, 8));
        System.out.println("[2] Form action: " + action);
        assertFalse(csrf.isEmpty(), "csrf must not be empty");
        assertFalse(relayState.isEmpty(), "relayState must not be empty");

        // ---- Step 3: POST email (username step) ----
        String identifierUrl = action.startsWith("http") ? action : AUTH_BASE_URL + action;
        String userBody = formBody(Map.of("_csrf", csrf, "relayState", relayState, "hmac", hmac, "email", user));

        resp = followHttpRedirects(client, URI.create(identifierUrl), post(client, identifierUrl, userBody));
        System.out.println("[3] After username POST: HTTP " + resp.statusCode());
        System.out.println("[3] Body snippet: " + crop(resp.body(), 1200));
        assertEquals(200, resp.statusCode(), "Expected password form page after username");

        // ---- Step 4: Parse password form (may be JS-rendered — fields embedded in script JSON) ----
        html = resp.body();
        // Extract identitykit hint so we know what page we are on
        String identitykit = extractMetaContent(html, "identitykit");
        System.out.println("[4] identitykit: " + identitykit);
        assertFalse("registerCredentials".equals(identitykit),
                "Email is not registered — got registration page instead of login");

        // Fields may be in static HTML <input>, JSON inside templateModel, or window._IDK JS props.
        // CSRF: VW puts it in window._IDK.csrf_token = '...' (single-quoted JS, not JSON "key":"val")
        String csrf2 = extractHiddenField(html, "_csrf");
        if (csrf2.isEmpty())
            csrf2 = extractJsonString(html, "_csrf");
        if (csrf2.isEmpty())
            csrf2 = extractJsProperty(html, "csrf_token");
        String relayState2 = extractHiddenField(html, "relayState");
        if (relayState2.isEmpty())
            relayState2 = extractJsonString(html, "relayState");
        String hmac2 = extractHiddenField(html, "hmac");
        if (hmac2.isEmpty())
            hmac2 = extractJsonString(html, "hmac");

        // Action: HTML form → "loginFormAction" JSON → "postAction" JSON (relative, resolve against idUrl)
        String action2 = extractFormAction(html);
        if (action2.isEmpty())
            action2 = extractJsonString(html, "loginFormAction");
        if (action2.isEmpty()) {
            String postAction = extractJsonString(html, "postAction");
            if (!postAction.isEmpty()) {
                // postAction is relative to the signin-service client root, e.g. "login/authenticate".
                // identifierUrl ends with "/login/identifier"; go up one level ("../") to reach
                // the client root, then resolve postAction from there.
                action2 = URI.create(identifierUrl).resolve("../").resolve(postAction).toString();
            }
        }
        System.out.printf("[4] action='%s' csrf='%s'… relayState='%s'… hmac='%s'…%n",
                action2, crop(csrf2, 8), crop(relayState2, 8), crop(hmac2, 8));
        if (action2.isEmpty()) {
            action2 = AUTH_BASE_URL + "/signin-service/v1/" + CLIENT_ID + "/login/authenticate";
            System.out.println("[4] action empty — using fallback: " + action2);
        }

        // ---- Step 5: POST password, intercept cupra:// redirect ----
        String authUrl = action2.startsWith("http") ? action2 : AUTH_BASE_URL + action2;
        // The authenticate endpoint expects all EmailPasswordForm fields: email + password + session fields
        String passBody = formBody(Map.of("_csrf", csrf2, "relayState", relayState2, "hmac", hmac2,
                "email", user, "password", pass));

        // Follow redirects manually so we can intercept cupra://
        // Add Referer + Origin so the server's CSRF check passes
        resp = client.send(HttpRequest.newBuilder(URI.create(authUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .header("Referer", identifierUrl)
                .header("Origin", AUTH_BASE_URL)
                .POST(HttpRequest.BodyPublishers.ofString(passBody)).build(), BodyHandlers.ofString());
        System.out.println("[5] Password POST initial response: HTTP " + resp.statusCode());
        if (resp.statusCode() / 100 != 3) {
            // Not a redirect — print body for diagnosis (could be error page, MFA, terms…)
            System.out.println("[5] Non-redirect body: " + crop(resp.body(), 800));
        }
        String callbackUrl = null;
        URI currentUri = URI.create(authUrl);
        int depth = 0;
        while (resp.statusCode() / 100 == 3 && depth++ < 20) {
            String loc = resp.headers().firstValue("location").orElse("");
            System.out.println("[5] Redirect[" + depth + "] → " + crop(loc, 120));
            if (loc.isEmpty())
                break;
            URI next = currentUri.resolve(loc); // handles relative redirects
            if (!next.getScheme().startsWith("http")) {
                callbackUrl = next.toString(); // e.g. cupra://oauth-callback?code=…
                break;
            }
            currentUri = next;
            resp = client.send(
                    HttpRequest.newBuilder(next).header("User-Agent", USER_AGENT).GET().build(),
                    BodyHandlers.ofString());
            // Print non-redirect responses reached mid-chain
            if (resp.statusCode() / 100 != 3) {
                System.out.println("[5] Chain landed on HTTP " + resp.statusCode() + " at " + currentUri);
                System.out.println("[5] Body snippet: " + crop(resp.body(), 400));
            }
        }
        System.out.println("[5] Loop exit — callbackUrl=" + callbackUrl + " depth=" + depth
                + " lastStatus=" + resp.statusCode());
        assertNotNull(callbackUrl, "Expected cupra://oauth-callback redirect");
        assertTrue(callbackUrl.startsWith("cupra://"), "Callback URL should start with cupra://");

        // ---- Step 6: Extract code + id_token + access_token from callback URL ----
        // Callback uses fragment (#), not query (?), but parseQueryString handles the whole string.
        String query = callbackUrl.substring(callbackUrl.indexOf('?') + 1);
        Map<String, String> cbParams = parseQueryString(query);
        String code = cbParams.getOrDefault("code", "");
        String idToken = cbParams.getOrDefault("id_token", "");
        String cbAccessToken = cbParams.getOrDefault("access_token", ""); // implicit-flow token from hybrid response

        System.out.println("[6] code          = " + crop(code, 16) + "…");
        System.out.println("[6] id_token      = " + crop(idToken, 16) + "…");
        System.out.println("[6] access_token  = " + crop(cbAccessToken, 16) + "…");
        assertFalse(code.isEmpty(), "Authorization code must not be empty");

        // ---- Step 7: Exchange code for tokens ----
        String tokenBody = formBody(
                Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI, "client_id",
                        CLIENT_ID, "client_secret", CLIENT_SECRET, "state", state, "id_token", idToken));

        HttpResponse<String> tokenResp = client.send(buildPost(TOKEN_URL, tokenBody), BodyHandlers.ofString());
        System.out.println("[7] Token exchange: HTTP " + tokenResp.statusCode());
        // Log full token response to inspect all fields (wc_access_token, token_type, etc.)
        System.out.println("[7] Response: " + tokenResp.body());
        assertEquals(200, tokenResp.statusCode(), "Token exchange failed: " + tokenResp.body());

        String accessToken = extractJsonString(tokenResp.body(), "access_token");
        String refreshToken = extractJsonString(tokenResp.body(), "refresh_token");
        String wcAccessToken = extractJsonString(tokenResp.body(), "wc_access_token");
        System.out.println("[7] access_token    = " + crop(accessToken, 16) + "…");
        System.out.println("[7] refresh_token   = " + crop(refreshToken, 16) + "…");
        System.out.println("[7] wc_access_token = " + (wcAccessToken.isEmpty() ? "(not present)" : crop(wcAccessToken, 16) + "…"));
        assertFalse(accessToken.isEmpty(), "access_token must not be empty");

        // ---- Step 8: GET /vehicles ----
        // 200 = success (vehicles list returned)
        // 403 = account authenticated but no vehicle linked in MyCUPRA app yet (valid auth state)
        // 401 = token rejected (auth failure — should not happen if steps 1-7 passed)
        HttpResponse<String> vehicleResp = client.send(
                HttpRequest.newBuilder().uri(URI.create(API_BASE + "/vehicles"))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Accept-Language", "de-de")
                        .header("X-App-Name", "MyCUPRA")
                        .header("X-App-Version", "1.0")
                        .header("X-Country-Id", "DE")
                        .header("X-Language-Id", "de")
                        .GET().build(),
                BodyHandlers.ofString());
        System.out.println("[8] GET /vehicles: HTTP " + vehicleResp.statusCode());
        System.out.println("[8] Response: " + vehicleResp.body());
        assertNotEquals(401, vehicleResp.statusCode(),
                "Token was rejected — authentication failed: " + vehicleResp.body());
        assertTrue(vehicleResp.statusCode() == 200 || vehicleResp.statusCode() == 403,
                "Unexpected status (expected 200 or 403): " + vehicleResp.body());
        if (vehicleResp.statusCode() == 403) {
            System.out.println("[8] 403 = no vehicle registered in MyCUPRA app (valid account state)");
        }

        // ---- Step 9: Smoke-test token refresh ----
        String refreshBody = formBody(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id",
                CLIENT_ID, "client_secret", CLIENT_SECRET));

        HttpResponse<String> refreshResp = client.send(buildPost(TOKEN_URL, refreshBody), BodyHandlers.ofString());
        System.out.println("[9] Token refresh: HTTP " + refreshResp.statusCode());
        System.out.println("[9] Response: " + crop(refreshResp.body(), 300));
        assertEquals(200, refreshResp.statusCode(), "Token refresh failed: " + refreshResp.body());
        assertFalse(extractJsonString(refreshResp.body(), "access_token").isEmpty(),
                "Refreshed access_token must not be empty");
    }

    // ================================================================== //
    // Helpers //
    // ================================================================== //

    private HttpClient httpClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private HttpClient httpClientWithCookies() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Follow HTTP 3xx redirects, resolving relative Location headers against {@code base}.
     * Stops at non-http(s) schemes (e.g. {@code cupra://}).
     */
    private HttpResponse<String> followHttpRedirects(HttpClient client, URI base, HttpResponse<String> resp)
            throws Exception {
        URI current = base;
        int depth = 0;
        while (resp.statusCode() / 100 == 3 && depth++ < 20) {
            String loc = resp.headers().firstValue("location").orElse("");
            if (loc.isEmpty())
                break;
            URI next = current.resolve(loc); // handles both absolute and relative
            if (!next.getScheme().startsWith("http"))
                break;
            current = next;
            resp = client.send(
                    HttpRequest.newBuilder(next).header("User-Agent", USER_AGENT).GET().build(),
                    BodyHandlers.ofString());
        }
        return resp;
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).GET().build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
        return client.send(buildPost(url, body), BodyHandlers.ofString());
    }

    private HttpRequest buildPost(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    /** URL-encode all key=value pairs into an application/x-www-form-urlencoded body. */
    private String formBody(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0)
                sb.append('&');
            sb.append(enc(k)).append('=').append(enc(v));
        });
        return sb.toString();
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Extract {@code value} attribute of {@code <input name="…">} from HTML. */
    private String extractHiddenField(String html, String name) {
        // attribute order: name before value
        Matcher m = Pattern.compile("<input[^>]+name=\"" + Pattern.quote(name) + "\"[^>]+value=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find())
            return m.group(1);
        // attribute order: value before name
        m = Pattern.compile("<input[^>]+value=\"([^\"]+)\"[^>]+name=\"" + Pattern.quote(name) + "\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find())
            return m.group(1);
        return "";
    }

    /** Extract the {@code action} URL from the first {@code <form>} tag in HTML (single or double quotes). */
    private String extractFormAction(String html) {
        // double-quoted action
        Matcher m = Pattern.compile("<form[^>]+action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find())
            return m.group(1);
        // single-quoted action
        m = Pattern.compile("<form[^>]+action='([^']+)'", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find())
            return m.group(1);
        return "";
    }

    /**
     * Extract a value from an unquoted JavaScript property assignment, e.g.
     * {@code csrf_token: 'abc123'} or {@code csrf_token: "abc123"}.
     */
    private String extractJsProperty(String html, String propName) {
        Matcher m = Pattern.compile(Pattern.quote(propName) + "\\s*:\\s*'([^']+)'").matcher(html);
        if (m.find())
            return m.group(1);
        m = Pattern.compile(Pattern.quote(propName) + "\\s*:\\s*\"([^\"]+)\"").matcher(html);
        if (m.find())
            return m.group(1);
        return "";
    }

    /** Extract the {@code content} attribute of {@code <meta name="…">} from HTML. */
    private String extractMetaContent(String html, String name) {
        Matcher m = Pattern.compile("<meta[^>]+name=\"" + Pattern.quote(name) + "\"[^>]+content=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find())
            return m.group(1);
        m = Pattern.compile("<meta[^>]+content=\"([^\"]+)\"[^>]+name=\"" + Pattern.quote(name) + "\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find())
            return m.group(1);
        return "";
    }

    /** Parse a query string into a key→value map, URL-decoding both sides. */
    private Map<String, String> parseQueryString(String query) {
        Map<String, String> map = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }

    /** Extract a string value from a flat JSON object (no nested parsing). */
    private String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private String crop(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
