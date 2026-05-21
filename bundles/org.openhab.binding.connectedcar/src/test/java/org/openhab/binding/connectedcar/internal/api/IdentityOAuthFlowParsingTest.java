/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.connectedcar.internal.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the response-parsing logic in {@link IdentityOAuthFlow}.
 *
 * Uses a {@link StubHttpClient} to inject pre-baked response bodies and/or
 * redirect Location URLs so that the private {@code update()} method can be
 * exercised without any network access.
 *
 * Scenarios covered:
 * <ol>
 * <li>Classic HTML {@code <input>} fields (legacy VW Group / CarNet)</li>
 * <li>JSON {@code "hmac":"..."} (WeConnect)</li>
 * <li>JSON {@code "relayState":"..."} with and without space (CUPRA window._IDK)</li>
 * <li>JS {@code csrf_token: '...'} with and without space (CUPRA window._IDK)</li>
 * <li>Priority: HTML fields win over JS/JSON equivalents</li>
 * <li>Redirect URL parsing – CUPRA fragment callback and standard VW query params</li>
 * <li>Error detection – four Location URL patterns that must throw exceptions</li>
 * </ol>
 */
class IdentityOAuthFlowParsingTest {

    /**
     * {@link ApiResult} subclass that exposes a controllable Location header value,
     * since the original field is private and only populated via Jetty's ContentResponse.
     */
    static class StubApiResult extends ApiResult {
        private final String loc;

        StubApiResult(String body, String location) {
            this.response = body;
            this.loc = location;
        }

        @Override
        public String getLocation() {
            return loc;
        }
    }

    /**
     * Minimal HTTP stub – returns a fixed response body and redirect location
     * for every request, without touching the network.
     */
    static class StubHttpClient extends ApiHttpClient {
        private final String body;
        private final String location;

        StubHttpClient(String body) {
            this(body, "");
        }

        StubHttpClient(String body, String location) {
            super(); // creates an unstarted Jetty HttpClient; clearCookies() is overridden below
            this.body = body;
            this.location = location;
        }

        @Override
        public ApiResult get(String uri, Map<String, String> headers, boolean followRedirect) {
            return new StubApiResult(body, location);
        }

        @Override
        public void clearCookies() {
            // no-op – no real Jetty lifecycle needed in tests
        }
    }

    private IdentityOAuthFlow flowWithBody(String html) {
        return new IdentityOAuthFlow(new StubHttpClient(html));
    }

    private IdentityOAuthFlow flowWithBodyAndLocation(String html, String location) {
        return new IdentityOAuthFlow(new StubHttpClient(html, location));
    }

    // ── Classic HTML <input> fields (legacy VW Group / CarNet) ──────────────────

    @Test
    void htmlInputFieldsAreExtracted() throws Exception {
        IdentityOAuthFlow oauth = flowWithBody(
                "<form action=\"/signin-service/v1/abc/login/identifier\">"
                        + "<input name=\"_csrf\" value=\"HTML-CSRF\"/>"
                        + "<input name=\"relayState\" value=\"HTML-RELAY\"/>"
                        + "<input name=\"hmac\" value=\"HTML-HMAC\"/>"
                        + "</form>");
        oauth.get("https://example.com");

        assertEquals("HTML-CSRF", oauth.csrf, "csrf from HTML input");
        assertEquals("HTML-RELAY", oauth.relayState, "relayState from HTML input");
        assertEquals("HTML-HMAC", oauth.hmac, "hmac from HTML input");
        assertEquals("/signin-service/v1/abc/login/identifier", oauth.action, "action URL");
    }

    // ── JSON hmac (WeConnect) ────────────────────────────────────────────────────

    @Test
    void jsonHmacIsExtracted() throws Exception {
        IdentityOAuthFlow oauth = flowWithBody(
                "{\"hmac\":\"json-hmac\",\"postAction\":\"authenticate\"}");
        oauth.get("https://example.com");

        assertEquals("json-hmac", oauth.hmac, "hmac from JSON");
    }

    // ── CUPRA / new VW Group: JSON relayState (window._IDK templateModel) ────────

    @Test
    void cupraJsonRelayStateWithSpaceIsExtracted() throws Exception {
        // Format actually seen in CUPRA identity page: space after colon
        IdentityOAuthFlow oauth = flowWithBody(
                "\"relayState\": \"relay-with-space\"");
        oauth.get("https://example.com");

        assertEquals("relay-with-space", oauth.relayState, "relayState from JSON (with space)");
    }

    @Test
    void cupraJsonRelayStateNoSpaceIsExtracted() throws Exception {
        IdentityOAuthFlow oauth = flowWithBody(
                "\"relayState\":\"relay-no-space\"");
        oauth.get("https://example.com");

        assertEquals("relay-no-space", oauth.relayState, "relayState from JSON (no space)");
    }

    // ── CUPRA / new VW Group: JS csrf_token (window._IDK) ───────────────────────

    @Test
    void cupraCsrfTokenWithSpaceIsExtracted() throws Exception {
        // Format from MyCUPRA app identity page: space between colon and single-quote
        IdentityOAuthFlow oauth = flowWithBody(
                "window._IDK = {csrf_token: 'TOKEN-SPACE'}");
        oauth.get("https://example.com");

        assertEquals("TOKEN-SPACE", oauth.csrf, "csrf from window._IDK (with space)");
    }

    @Test
    void cupraCsrfTokenNoSpaceIsExtracted() throws Exception {
        IdentityOAuthFlow oauth = flowWithBody(
                "window._IDK = {csrf_token:'TOKEN-NOSPACE'}");
        oauth.get("https://example.com");

        assertEquals("TOKEN-NOSPACE", oauth.csrf, "csrf from window._IDK (no space)");
    }

    // ── Priority: HTML wins over JS/JSON ─────────────────────────────────────────

    @Test
    void htmlCsrfTakesPriorityOverJsCsrfToken() throws Exception {
        IdentityOAuthFlow oauth = flowWithBody(
                "<input name=\"_csrf\" value=\"HTML-WINS\"/>"
                        + " window._IDK = {csrf_token: 'JS-LOSES'}");
        oauth.get("https://example.com");

        assertEquals("HTML-WINS", oauth.csrf, "HTML _csrf should take priority over JS csrf_token");
    }

    @Test
    void htmlRelayStateTakesPriorityOverJsonRelayState() throws Exception {
        IdentityOAuthFlow oauth = flowWithBody(
                "<input name=\"relayState\" value=\"HTML-RELAY\"/>"
                        + " {\"relayState\":\"JSON-LOSES\"}");
        oauth.get("https://example.com");

        assertEquals("HTML-RELAY", oauth.relayState, "HTML relayState input should take priority over JSON");
    }

    // ── Redirect URL parsing ─────────────────────────────────────────────────────

    /**
     * CUPRA uses a fragment-based OAuth callback:
     * {@code cupra://oauth-callback#state=...&code=...&id_token=...&access_token=...}
     * The {@code state} parameter uses '#' as separator; the others use '&'.
     */
    @Test
    void cupraFragmentCallbackIsFullyParsed() throws Exception {
        String callbackUrl = "cupra://oauth-callback"
                + "#state=STATE123"
                + "&code=AUTH-CODE"
                + "&id_token=ID-TOKEN"
                + "&access_token=ACCESS-TOKEN"
                + "&expires_in=3600";
        IdentityOAuthFlow oauth = flowWithBodyAndLocation("", callbackUrl);
        oauth.get("https://example.com");

        assertEquals("STATE123", oauth.state, "state from fragment");
        assertEquals("AUTH-CODE", oauth.code, "code from fragment");
        assertEquals("ID-TOKEN", oauth.idToken, "id_token from fragment");
        assertEquals("ACCESS-TOKEN", oauth.accessToken, "access_token from fragment");
        assertEquals("3600", oauth.expiresIn, "expires_in from fragment");
    }

    /**
     * Standard VW Group redirect during the login flow carries relayState and hmac
     * as query parameters on the Location header.
     */
    @Test
    void redirectQueryParamsAreExtracted() throws Exception {
        String redirectUrl = "/signin-service/v1/abc/login/authenticate?relayState=RELAY1&hmac=HMAC1";
        IdentityOAuthFlow oauth = flowWithBodyAndLocation("", redirectUrl);
        oauth.get("https://example.com");

        assertEquals("RELAY1", oauth.relayState, "relayState from redirect query param");
        assertEquals("HMAC1", oauth.hmac, "hmac from redirect query param");
    }

    // ── Error detection ──────────────────────────────────────────────────────────

    @Test
    void passwordInvalidLocationThrowsSecurityException() {
        IdentityOAuthFlow oauth = flowWithBodyAndLocation("",
                "https://identity.vwgroup.io/signin-service/v1/abc/identifier"
                        + "?error=login.errors.password_invalid");

        assertThrows(ApiSecurityException.class, () -> oauth.get("https://example.com"),
                "password_invalid in Location must throw ApiSecurityException");
    }

    @Test
    void throttledLocationThrowsSecurityException() {
        IdentityOAuthFlow oauth = flowWithBodyAndLocation("",
                "https://identity.vwgroup.io/signin-service/v1/abc/identifier"
                        + "?error=login.errors.throttled");

        assertThrows(ApiSecurityException.class, () -> oauth.get("https://example.com"),
                "throttled in Location must throw ApiSecurityException");
    }

    @Test
    void dataPrivacyUpdateLocationThrowsSecurityException() {
        IdentityOAuthFlow oauth = flowWithBodyAndLocation("",
                "https://identity.vwgroup.io/signin-service/v1/abc/login?&updated=dataprivacy");

        assertThrows(ApiSecurityException.class, () -> oauth.get("https://example.com"),
                "dataprivacy update in Location must throw ApiSecurityException");
    }

    @Test
    void termsAndConditionsLocationThrowsApiException() {
        IdentityOAuthFlow oauth = flowWithBodyAndLocation("",
                "https://identity.vwgroup.io/terms-and-conditions");

        assertThrows(ApiException.class, () -> oauth.get("https://example.com"),
                "terms-and-conditions in Location must throw ApiException");
    }
}
