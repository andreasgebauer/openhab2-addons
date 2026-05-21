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

import static org.openhab.binding.connectedcar.internal.BindingConstants.CONTENT_TYPE_FORM_URLENC;
import static org.openhab.binding.connectedcar.internal.api.ApiDataTypesDTO.API_BRAND_CUPRA;
import static org.openhab.binding.connectedcar.internal.util.Helpers.fromJson;

import javax.ws.rs.core.HttpHeaders;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.http.HttpHeader;
import org.openhab.binding.connectedcar.internal.api.ApiBrandProperties;
import org.openhab.binding.connectedcar.internal.api.ApiEventListener;
import org.openhab.binding.connectedcar.internal.api.ApiException;
import org.openhab.binding.connectedcar.internal.api.ApiHttpClient;
import org.openhab.binding.connectedcar.internal.api.ApiHttpMap;
import org.openhab.binding.connectedcar.internal.api.ApiIdentity;
import org.openhab.binding.connectedcar.internal.api.ApiIdentity.OAuthToken;
import org.openhab.binding.connectedcar.internal.api.BrandAuthenticator;
import org.openhab.binding.connectedcar.internal.api.IdentityManager;
import org.openhab.binding.connectedcar.internal.api.IdentityOAuthFlow;
import org.openhab.binding.connectedcar.internal.api.weconnect.WeConnectApi;
import org.openhab.binding.connectedcar.internal.handler.ThingHandlerInterface;

/**
 * {@link BrandCupra} provides the CUPRA Born specific functions of the API.
 * The Born uses the WeConnect-Cupra backend (ola.prod.code.seat.cloud.vwgroup.com)
 * with standard VW Group OIDC authentication via identity.vwgroup.io.
 *
 * @author Andreas Gebauer - Initial contribution
 */
@NonNullByDefault
public class BrandCupra extends WeConnectApi implements BrandAuthenticator {

    private static final String CUPRA_API_BASE_URL = "https://ola.prod.code.seat.cloud.vwgroup.com";
    private static final String CUPRA_TOKEN_URL = "https://identity.vwgroup.io/oidc/v1/token";
    private static final String CUPRA_CLIENT_ID = "3c756d46-f1ba-4d78-9f9a-cff0d5292d51@apps_vw-dilab_com";
    // Client secret is embedded in the MyCUPRA app binary (not a user-managed secret)
    private static final String CUPRA_CLIENT_SECRET = "eb8814e641c81a2640ad62eeccec11c98effc9bccd4269ab7af338b50a94b3a2";

    static ApiBrandProperties properties = new ApiBrandProperties();
    static {
        properties.brand = API_BRAND_CUPRA;
        properties.apiDefaultUrl = CUPRA_API_BASE_URL;
        properties.userAgent = "CUPRAApp%20-%20Store/20220503 CFNetwork/1333.0.4 Darwin/21.5.0";
        properties.xcountry = "DE";
        properties.tokenUrl = CUPRA_TOKEN_URL;
        properties.tokenRefreshUrl = CUPRA_TOKEN_URL;
        properties.clientId = CUPRA_CLIENT_ID;
        properties.authScope = "openid profile nickname birthdate phone";
        properties.redirect_uri = "cupra://oauth-callback";
        properties.responseType = "code id_token token";
        properties.xrequest = "com.cupra.mycupra";
        properties.xappName = "MyCUPRA";
        properties.xappVersion = "1.0";

        properties.stdHeaders.put(HttpHeader.USER_AGENT.toString(), properties.userAgent);
        properties.stdHeaders.put(HttpHeaders.ACCEPT, "application/json");
        properties.stdHeaders.put(HttpHeader.ACCEPT_LANGUAGE.toString(), "de-de");
    }

    public BrandCupra(ThingHandlerInterface handler, ApiHttpClient httpClient, IdentityManager tokenManager,
            @Nullable ApiEventListener eventListener) {
        super(handler, httpClient, tokenManager, eventListener);
    }

    @Override
    public ApiBrandProperties getProperties() {
        return properties;
    }

    @Override
    public ApiIdentity grantAccess(IdentityOAuthFlow oauth) throws ApiException {
        String json = oauth.clearHeader().header(HttpHeader.HOST, "identity.vwgroup.io")
                .header(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_FORM_URLENC).clearData().data("code", oauth.code)
                .data("redirect_uri", config.api.redirect_uri).data("client_id", config.api.clientId)
                .data("client_secret", CUPRA_CLIENT_SECRET).data("grant_type", "authorization_code")
                .data("state", oauth.state).data("id_token", oauth.idToken).post(config.api.tokenUrl, true).response;
        return new ApiIdentity(fromJson(gson, json, OAuthToken.class).normalize());
    }

    @Override
    public OAuthToken refreshToken(ApiIdentity token) throws ApiException {
        ApiHttpMap headers = new ApiHttpMap().header(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_FORM_URLENC);
        String body = "client_id=" + config.api.clientId + "&client_secret=" + CUPRA_CLIENT_SECRET
                + "&grant_type=refresh_token" + "&refresh_token=" + token.getRefreshToken();
        String json = http.post(config.api.tokenRefreshUrl, headers.getHeaders(), body).response;
        return fromJson(gson, json, OAuthToken.class);
    }
}
