/*
 * Copyright (c) 2017-2024, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth.endpoint.revoke;

import org.apache.axiom.util.base64.Base64Utils;
import org.apache.commons.collections.iterators.IteratorEnumeration;
import org.apache.commons.lang.StringUtils;
import org.apache.oltu.oauth2.common.OAuth;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.base.CarbonBaseConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.context.internal.OSGiDataHolder;
import org.wso2.carbon.identity.central.log.mgt.utils.LoggerUtils;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.oauth.common.OAuth2ErrorCodes;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth.endpoint.exception.InvalidRequestParentException;
import org.wso2.carbon.identity.oauth.endpoint.expmapper.InvalidRequestExceptionMapper;
import org.wso2.carbon.identity.oauth.endpoint.util.factory.OAuth2ServiceFactory;
import org.wso2.carbon.identity.oauth2.OAuth2Service;
import org.wso2.carbon.identity.oauth2.ResponseHeader;
import org.wso2.carbon.identity.oauth2.dto.OAuthRevocationRequestDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuthRevocationResponseDTO;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class OAuthRevocationEndpointTest {

    @Mock
    OAuthServerConfiguration mockOAuthServerConfiguration;

    @Mock
    OAuthRevocationResponseDTO oAuthRevocationResponseDTO;

    @Mock
    OAuth2Service oAuth2Service;

    @Mock
    BundleContext bundleContext;

    MockedConstruction<ServiceTracker> mockedConstruction;

    private static final String TOKEN_PARAM = "token";
    private static final String TOKEN_TYPE_HINT_PARAM = "token_type_hint";
    private static final String CALLBACK_PARAM = "callback";
    private static final String CLIENT_ID_VALUE = "ca19a540f544777860e44e75f605d927";
    private static final String SECRET = "87n9a540f544777860e44e75f605d435";
    private static final String APP_REDIRECT_URL = "http://localhost:8080/redirect";
    private static final String ACCESS_TOKEN = "1234-542230-45220-54245";
    private static final String TOKEN_HINT = "1234-542230-45220-54245";
    private static final String AUTHORIZATION_HEADER =
            "Basic " + Base64Utils.encode((CLIENT_ID_VALUE + ":" + SECRET).getBytes());

    private OAuthRevocationEndpoint revocationEndpoint;

    private MockedStatic<OAuthServerConfiguration> oAuthServerConfiguration;

    // TODO move this to Before class
    @BeforeTest
    public void setUp() {

        System.setProperty(
                CarbonBaseConstants.CARBON_HOME,
                Paths.get(System.getProperty("user.dir"), "src", "test", "resources").toString()
        );
        revocationEndpoint = new OAuthRevocationEndpoint();
    }

    @BeforeMethod
    public void setUpBeforeMethod() {

        initMocks(this);
        oAuthServerConfiguration = mockStatic(OAuthServerConfiguration.class);
        oAuthServerConfiguration.when(OAuthServerConfiguration::getInstance).thenReturn(mockOAuthServerConfiguration);

        PrivilegedCarbonContext.startTenantFlow();
        PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain("carbon.super");

        ArgumentCaptor<String> argumentCaptor = ArgumentCaptor.forClass(String.class);
        mockedConstruction = mockConstruction(ServiceTracker.class,
                (mock, context) -> {
                    verify(bundleContext, atLeastOnce()).createFilter(argumentCaptor.capture());
                    if (argumentCaptor.getValue().contains(OAuth2Service.class.getName())) {
                        when(mock.getServices()).thenReturn(new Object[]{oAuth2Service});
                    }
                });
        OSGiDataHolder.getInstance().setBundleContext(bundleContext);
    }

    @AfterMethod
    public void tearDown() {

        oAuthServerConfiguration.close();
        mockedConstruction.close();
        PrivilegedCarbonContext.endTenantFlow();
    }

    @DataProvider(name = "testRevokeAccessTokenDataProvider")
    public Object[][] testRevokeAccessTokenDataProvider() {

        String inCorrectAuthzHeader = "Basic value1 value2";
        ResponseHeader contentType = new ResponseHeader();
        contentType.setKey(OAuth.HeaderType.CONTENT_TYPE);
        contentType.setValue(OAuth.ContentType.URL_ENCODED);

        ResponseHeader[] headers1 = new ResponseHeader[]{contentType};
        ResponseHeader[] headers2 = new ResponseHeader[]{null};
        ResponseHeader[] headers3 = new ResponseHeader[0];

        return new Object[][]{
                // Client id and secret in both header and request params.
                // Will return unauthorized error since multiple methods of authentication
                {AUTHORIZATION_HEADER, true, ACCESS_TOKEN, TOKEN_HINT, APP_REDIRECT_URL, CLIENT_ID_VALUE, SECRET,
                        OAuth2ErrorCodes.INVALID_CLIENT, null, null, HttpServletResponse.SC_UNAUTHORIZED,
                        OAuth2ErrorCodes.INVALID_CLIENT},

                // Client id and secret in both header and request params.
                // Will return unauthorized error since multiple methods of authentication
                {AUTHORIZATION_HEADER, false, ACCESS_TOKEN, TOKEN_HINT, null, CLIENT_ID_VALUE, SECRET,
                        OAuth2ErrorCodes.INVALID_CLIENT, null, null, HttpServletResponse.SC_UNAUTHORIZED,
                        OAuth2ErrorCodes.INVALID_CLIENT},

                // Invalid authz header. Will return unauthorized error response.
                {inCorrectAuthzHeader, true, ACCESS_TOKEN, null, "", null, null, OAuth2ErrorCodes.INVALID_CLIENT,
                        null, null, HttpServletResponse.SC_UNAUTHORIZED, OAuth2ErrorCodes.INVALID_CLIENT},

                // Will return bad request when the tocken is empty in the request
                {AUTHORIZATION_HEADER, true, null, null, "", null, null, OAuth2ErrorCodes.INVALID_CLIENT, null, null,
                        HttpServletResponse.SC_BAD_REQUEST, OAuth2ErrorCodes.INVALID_REQUEST},

                // Token not found in the request (callback is empty). Will return bad request error response.
                {AUTHORIZATION_HEADER, false, null, null, "", null, null, null, null, null,
                        HttpServletResponse.SC_BAD_REQUEST, OAuth2ErrorCodes.INVALID_REQUEST},

                // Token not found in the request (callback is null). Will return bad request error response.
                {AUTHORIZATION_HEADER, false, null, null, null, null, null, null, null, null,
                        HttpServletResponse.SC_BAD_REQUEST, OAuth2ErrorCodes.INVALID_REQUEST},

                // Auth revocation response has invalid client error. Will return unauthorized error response.
                {AUTHORIZATION_HEADER, true, ACCESS_TOKEN, TOKEN_HINT, "", null, SECRET,
                        OAuth2ErrorCodes.INVALID_CLIENT, null, null, HttpServletResponse.SC_UNAUTHORIZED,
                        OAuth2ErrorCodes.INVALID_CLIENT},

                // Auth revocation response has unauthorized client error (with callback value).
                // Will return unauthorized error response.
                {AUTHORIZATION_HEADER, true, ACCESS_TOKEN, TOKEN_HINT, APP_REDIRECT_URL, CLIENT_ID_VALUE, null,
                        OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, null, null, HttpServletResponse.SC_UNAUTHORIZED,
                        OAuth2ErrorCodes.UNAUTHORIZED_CLIENT},

                // Auth revocation response has unauthorized client error (callback is empty).
                // Will return unauthorized error response.
                {AUTHORIZATION_HEADER, true, ACCESS_TOKEN, TOKEN_HINT, "", null, null,
                        OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, null, null, HttpServletResponse.SC_UNAUTHORIZED,
                        OAuth2ErrorCodes.UNAUTHORIZED_CLIENT},

                // No authz header and client id, secret params (with callback value).
                // Auth revocation response will return invalid request error. Will return bad request error response.
                {null, true, ACCESS_TOKEN, null, APP_REDIRECT_URL, null, null, OAuth2ErrorCodes.INVALID_REQUEST, null,
                        null, HttpServletResponse.SC_BAD_REQUEST, OAuth2ErrorCodes.INVALID_REQUEST},

                // No authz header and client id, secret params (callback is empty).
                // Auth revocation response will return invalid request error. Will return bad request error response.
                {null, true, ACCESS_TOKEN, null, "", null, null, OAuth2ErrorCodes.INVALID_REQUEST, null, null,
                        HttpServletResponse.SC_BAD_REQUEST, OAuth2ErrorCodes.INVALID_REQUEST},

                // Correct authz header, Access token sent as a parameter, no client id and secret parameters
                // (No headers in the request). Will return 200 ok
                {AUTHORIZATION_HEADER, true, ACCESS_TOKEN, TOKEN_HINT, APP_REDIRECT_URL, null, null, null, null, null,
                        HttpServletResponse.SC_OK, null},

                // No authz header, Access token sent as a parameter,
                // client id and secret sent as params (with content type header). Will return 200 ok
                {null, true, ACCESS_TOKEN, TOKEN_HINT, APP_REDIRECT_URL, CLIENT_ID_VALUE, SECRET, null, headers1, null,
                        HttpServletResponse.SC_OK, null},

                // No authz header, Access token sent as a parameter,
                // client id and secret sent as params (header with null value). Will return 200 ok
                {null, true, ACCESS_TOKEN, TOKEN_HINT, "", CLIENT_ID_VALUE, SECRET, null, headers2, null,
                        HttpServletResponse.SC_OK, null},

                // No authz header, Access token sent as a parameter,
                // client id and secret sent as params (header array without values). Will return 200 ok
                {null, true, ACCESS_TOKEN, TOKEN_HINT, APP_REDIRECT_URL, CLIENT_ID_VALUE, SECRET, null, headers3, null,
                        HttpServletResponse.SC_OK, null},
        };
    }

    @Test(dataProvider = "testRevokeAccessTokenDataProvider")
    public void testRevokeAccessToken(String authzHeader, boolean addReqParams, String token, String tokenHint,
                                      String callback, String clientId, String secret, String respError,
                                      Object headerObj, Exception e, int expectedStatus, String expectedErrorCode)
            throws Exception {

        try (MockedStatic<IdentityTenantUtil> identityTenantUtil = mockStatic(IdentityTenantUtil.class);
             MockedStatic<LoggerUtils> loggerUtils = mockStatic(LoggerUtils.class);
             MockedStatic<OAuth2ServiceFactory> oauth2ServiceFactory = mockStatic(OAuth2ServiceFactory.class)) {
            MultivaluedMap<String, String> parameterMap = new MultivaluedHashMap<>();
            ResponseHeader[] responseHeaders = (ResponseHeader[]) headerObj;
            parameterMap.add(TOKEN_PARAM, token);
            parameterMap.add(TOKEN_TYPE_HINT_PARAM, tokenHint);
            parameterMap.add(CALLBACK_PARAM, callback);

            Map<String, String[]> requestedParams = new HashMap<>();
            if (addReqParams) {
                requestedParams.put(TOKEN_PARAM, new String[]{""});
                requestedParams.put(TOKEN_TYPE_HINT_PARAM, new String[]{""});
                requestedParams.put(CALLBACK_PARAM, new String[]{""});
            }

            loggerUtils.when(LoggerUtils::isDiagnosticLogsEnabled).thenReturn(true);
            identityTenantUtil.when(() -> IdentityTenantUtil.getTenantId(anyString())).thenReturn(-1234);
            HttpServletRequest request = mockHttpRequest(requestedParams, new HashMap<>());
            when(request.getHeader(OAuthConstants.HTTP_REQ_HEADER_AUTHZ)).thenReturn(authzHeader);

            oauth2ServiceFactory.when(OAuth2ServiceFactory::getOAuth2Service).thenReturn(oAuth2Service);

            final OAuthRevocationRequestDTO[] revokeReqDTO;
            revokeReqDTO = new OAuthRevocationRequestDTO[1];
            doAnswer(new Answer<Object>() {
                @Override
                public Object answer(InvocationOnMock invocation) throws Throwable {

                    revokeReqDTO[0] = (OAuthRevocationRequestDTO) invocation.getArguments()[0];
                    return oAuthRevocationResponseDTO;
                }
            }).when(oAuth2Service).revokeTokenByOAuthClient(any(OAuthRevocationRequestDTO.class));
            when(oAuthRevocationResponseDTO.getErrorCode()).thenReturn(respError);
            when(oAuthRevocationResponseDTO.getErrorMsg()).thenReturn(respError);
            when(oAuthRevocationResponseDTO.getResponseHeaders()).thenReturn(responseHeaders);

            Response response;
            try {
                response = revocationEndpoint.revokeAccessToken(request, parameterMap);
            } catch (InvalidRequestParentException ire) {
                InvalidRequestExceptionMapper invalidRequestExceptionMapper = new InvalidRequestExceptionMapper();
                response = invalidRequestExceptionMapper.toResponse(ire);
            }
            assertNotNull(response, "Token response is null");
            assertEquals(response.getStatus(), expectedStatus, "Unexpected HTTP response status");

            assertNotNull(response.getEntity(), "Response entity is null");
            if (expectedErrorCode != null) {
                assertTrue(response.getEntity().toString().contains(expectedErrorCode),
                        "Expected error code not found");
                if (StringUtils.isNotEmpty(callback)) {
                    assertTrue(response.getEntity().toString().contains(callback),
                            "Callback is not added to the response");
                }
            }
        }
    }

    private HttpServletRequest mockHttpRequest(final Map<String, String[]> requestParams,
                                               final Map<String, Object> requestAttributes) {

        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {

                String key = (String) invocation.getArguments()[0];
                return requestParams.get(key) != null ? requestParams.get(key)[0] : null;
            }
        }).when(httpServletRequest).getParameter(anyString());

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {

                String key = (String) invocation.getArguments()[0];
                return requestAttributes.get(key);
            }
        }).when(httpServletRequest).getAttribute(anyString());

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {

                String key = (String) invocation.getArguments()[0];
                Object value = invocation.getArguments()[1];
                requestAttributes.put(key, value);
                return null;
            }
        }).when(httpServletRequest).setAttribute(anyString(), any());

        when(httpServletRequest.getParameterMap()).thenReturn(requestParams);
        when(httpServletRequest.getParameterNames()).thenReturn(
                new IteratorEnumeration(requestParams.keySet().iterator()));
        when(httpServletRequest.getMethod()).thenReturn(HttpMethod.POST);
        when(httpServletRequest.getContentType()).thenReturn(OAuth.ContentType.URL_ENCODED);

        return httpServletRequest;
    }
}
