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
package org.openhab.binding.connectedcar.internal.api.cupra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.openhab.binding.connectedcar.internal.api.ApiBrandProperties;

/**
 * Verifies that {@link BrandCupra} registers all properties that the base
 * {@link org.openhab.binding.connectedcar.internal.api.ApiWithOAuth#getLoginUrl} method
 * requires to build a complete OIDC authorize URL.
 *
 * These are static-field checks: no HTTP calls, no OSGi runtime needed.
 */
class BrandCupraPropertiesTest {

    // BrandCupra.properties is package-private — accessible from this package.
    private static final ApiBrandProperties PROPS = BrandCupra.properties;

    // ── Mandatory fields for ApiWithOAuth.getLoginUrl() ──────────────────────────

    @Test
    void mandatoryOidcPropertiesAreAllSet() {
        assertFalse(PROPS.clientId.isEmpty(), "clientId must be set (needed for authorize URL)");
        assertFalse(PROPS.authScope.isEmpty(), "authScope must be set (needed for authorize URL)");
        assertFalse(PROPS.responseType.isEmpty(), "responseType must be set (needed for authorize URL)");
        assertFalse(PROPS.redirect_uri.isEmpty(), "redirect_uri must be set (needed for authorize URL)");
        assertFalse(PROPS.issuerRegionMappingUrl.isEmpty(),
                "issuerRegionMappingUrl must be set (base URL for /oidc/v1/authorize)");
    }

    @Test
    void mandatoryTokenPropertiesAreAllSet() {
        assertFalse(PROPS.tokenUrl.isEmpty(), "tokenUrl must be set (code exchange)");
        assertFalse(PROPS.tokenRefreshUrl.isEmpty(), "tokenRefreshUrl must be set (token refresh)");
        assertFalse(PROPS.apiDefaultUrl.isEmpty(), "apiDefaultUrl must be set (vehicle API base)");
        assertFalse(PROPS.xrequest.isEmpty(), "xrequest must be set (x-requested-with header)");
        assertFalse(PROPS.xappName.isEmpty(), "xappName must be set (app identification header)");
    }

    // ── CUPRA-specific values ─────────────────────────────────────────────────────

    @Test
    void cupraUsesCustomUriSchemeForCallback() {
        assertEquals("cupra://oauth-callback", PROPS.redirect_uri,
                "CUPRA OAuth callback must use the custom URI scheme, not an HTTP URL");
    }

    @Test
    void cupraUsesHybridFlow() {
        assertEquals("code id_token token", PROPS.responseType,
                "CUPRA uses the OpenID Connect hybrid flow (code + id_token + token)");
    }

    @Test
    void cupraUsesStandardVwIdentityServer() {
        assertEquals("https://identity.vwgroup.io", PROPS.issuerRegionMappingUrl,
                "CUPRA authenticates via the shared VW Group identity server");
    }

    @Test
    void cupraAuthScopeIncludesOpenId() {
        assertTrue(PROPS.authScope.contains("openid"),
                "authScope must include 'openid' for OIDC compliance");
    }
}
