package com.auth0.client.auth;

import com.auth0.client.HttpOptions;
import com.auth0.client.LoggingOptions;
import com.auth0.client.MockServer;
import com.auth0.client.ProxyOptions;
import com.auth0.exception.APIException;
import com.auth0.json.auth.*;
import com.auth0.net.Request;
import com.auth0.net.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;
import okhttp3.logging.HttpLoggingInterceptor.Level;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import java.io.FileReader;
import java.net.Proxy;
import java.util.*;

import static com.auth0.client.MockServer.*;
import static com.auth0.client.RecordedRequestMatcher.hasHeader;
import static com.auth0.client.RecordedRequestMatcher.hasMethodAndPath;
import static com.auth0.client.UrlMatcher.hasQueryParameter;
import static com.auth0.client.UrlMatcher.isUrl;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.hamcrest.collection.IsMapContaining.hasKey;

public class AuthAPITest {

    private static final String DOMAIN = "domain.auth0.com";
    private static final String CLIENT_ID = "clientId";
    private static final String CLIENT_SECRET = "clientSecret";
    private static final String PASSWORD_STRENGTH_ERROR_RESPONSE_NONE = "src/test/resources/auth/password_strength_error_none.json";
    private static final String PASSWORD_STRENGTH_ERROR_RESPONSE_SOME = "src/test/resources/auth/password_strength_error_some.json";

    private MockServer server;
    private AuthAPI api;

    @SuppressWarnings("deprecation")
    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        server = new MockServer();
        api = new AuthAPI(server.getBaseUrl(), CLIENT_ID, CLIENT_SECRET);
    }

    @After
    public void tearDown() throws Exception {
        server.stop();
    }

    // Configuration

    @Test
    public void shouldAcceptDomainWithNoScheme() {
        AuthAPI api = new AuthAPI("me.something.com", CLIENT_ID, CLIENT_SECRET);

        assertThat(api.getBaseUrl(), is(notNullValue()));
        assertThat(api.getBaseUrl().toString(), isUrl("https", "me.something.com"));
    }

    @Test
    public void shouldAcceptDomainWithHttpScheme() {
        AuthAPI api = new AuthAPI("http://me.something.com", CLIENT_ID, CLIENT_SECRET);

        assertThat(api.getBaseUrl(), is(notNullValue()));
        assertThat(api.getBaseUrl().toString(), isUrl("http", "me.something.com"));
    }

    @Test
    public void shouldThrowWhenDomainIsInvalid() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("The domain had an invalid format and couldn't be parsed as an URL.");
        new AuthAPI("", CLIENT_ID, CLIENT_SECRET);
    }

    @Test
    public void shouldThrowWhenDomainIsNull() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'domain' cannot be null!");
        new AuthAPI(null, CLIENT_ID, CLIENT_SECRET);
    }

    @Test
    public void shouldThrowWhenClientIdIsNull() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'client id' cannot be null!");
        new AuthAPI(DOMAIN, null, CLIENT_SECRET);
    }

    @Test
    public void shouldThrowWhenClientSecretIsNull() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'client secret' cannot be null!");
        new AuthAPI(DOMAIN, CLIENT_ID, null);
    }

    @Test
    public void shouldUseDefaultTimeValues() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().connectTimeoutMillis(), is(10 * 1000));
        assertThat(api.getClient().readTimeoutMillis(), is(10 * 1000));
    }

    @Test
    public void shouldUseConfiguredTimeoutValues() {
        HttpOptions options = new HttpOptions();
        options.setConnectTimeout(20);
        options.setReadTimeout(30);
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, options);

        assertThat(api.getClient().connectTimeoutMillis(), is(20 * 1000));
        assertThat(api.getClient().readTimeoutMillis(), is(30 * 1000));
    }

    @Test
    public void shouldUseZeroIfNegativeTimoutConfigured() {
        HttpOptions options = new HttpOptions();
        options.setConnectTimeout(-1);
        options.setReadTimeout(-10);
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, options);

        assertThat(api.getClient().connectTimeoutMillis(), is(0));
        assertThat(api.getClient().readTimeoutMillis(), is(0));
    }

    @Test
    public void shouldNotUseProxyByDefault() throws Exception {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().proxy(), is(nullValue()));
        Authenticator authenticator = api.getClient().proxyAuthenticator();
        assertThat(authenticator, is(notNullValue()));

        Route route = Mockito.mock(Route.class);
        okhttp3.Request nonAuthenticatedRequest = new okhttp3.Request.Builder()
                .url("https://test.com/app")
                .addHeader("some-header", "some-value")
                .build();
        okhttp3.Response nonAuthenticatedResponse = new okhttp3.Response.Builder()
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .request(nonAuthenticatedRequest)
                .build();

        okhttp3.Request processedRequest = authenticator.authenticate(route, nonAuthenticatedResponse);
        assertThat(processedRequest, is(nullValue()));
    }

    @Test
    public void shouldUseProxy() throws Exception {
        Proxy proxy = Mockito.mock(Proxy.class);
        ProxyOptions proxyOptions = new ProxyOptions(proxy);
        HttpOptions httpOptions = new HttpOptions();
        httpOptions.setProxyOptions(proxyOptions);

        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, httpOptions);
        assertThat(api.getClient().proxy(), is(proxy));
        Authenticator authenticator = api.getClient().proxyAuthenticator();
        assertThat(authenticator, is(notNullValue()));

        Route route = Mockito.mock(Route.class);
        okhttp3.Request nonAuthenticatedRequest = new okhttp3.Request.Builder()
                .url("https://test.com/app")
                .addHeader("some-header", "some-value")
                .build();
        okhttp3.Response nonAuthenticatedResponse = new okhttp3.Response.Builder()
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .request(nonAuthenticatedRequest)
                .build();

        okhttp3.Request processedRequest = authenticator.authenticate(route, nonAuthenticatedResponse);

        assertThat(processedRequest, is(nullValue()));
    }

    @Test
    public void shouldUseProxyWithAuthentication() throws Exception {
        Proxy proxy = Mockito.mock(Proxy.class);
        ProxyOptions proxyOptions = new ProxyOptions(proxy);
        proxyOptions.setBasicAuthentication("johndoe", "psswd".toCharArray());
        assertThat(proxyOptions.getBasicAuthentication(), is("Basic am9obmRvZTpwc3N3ZA=="));
        HttpOptions httpOptions = new HttpOptions();
        httpOptions.setProxyOptions(proxyOptions);

        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, httpOptions);
        assertThat(api.getClient().proxy(), is(proxy));
        Authenticator authenticator = api.getClient().proxyAuthenticator();
        assertThat(authenticator, is(notNullValue()));

        Route route = Mockito.mock(Route.class);
        okhttp3.Request nonAuthenticatedRequest = new okhttp3.Request.Builder()
                .url("https://test.com/app")
                .addHeader("some-header", "some-value")
                .build();
        okhttp3.Response nonAuthenticatedResponse = new okhttp3.Response.Builder()
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .request(nonAuthenticatedRequest)
                .build();

        okhttp3.Request processedRequest = authenticator.authenticate(route, nonAuthenticatedResponse);

        assertThat(processedRequest, is(notNullValue()));
        assertThat(processedRequest.url(), is(HttpUrl.parse("https://test.com/app")));
        assertThat(processedRequest.header("Proxy-Authorization"), is(proxyOptions.getBasicAuthentication()));
        assertThat(processedRequest.header("some-header"), is("some-value"));
    }

    @Test
    public void proxyShouldNotProcessAlreadyAuthenticatedRequest() throws Exception {
        Proxy proxy = Mockito.mock(Proxy.class);
        ProxyOptions proxyOptions = new ProxyOptions(proxy);
        proxyOptions.setBasicAuthentication("johndoe", "psswd".toCharArray());
        assertThat(proxyOptions.getBasicAuthentication(), is("Basic am9obmRvZTpwc3N3ZA=="));
        HttpOptions httpOptions = new HttpOptions();
        httpOptions.setProxyOptions(proxyOptions);

        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, httpOptions);
        assertThat(api.getClient().proxy(), is(proxy));
        Authenticator authenticator = api.getClient().proxyAuthenticator();
        assertThat(authenticator, is(notNullValue()));

        Route route = Mockito.mock(Route.class);
        okhttp3.Request alreadyAuthenticatedRequest = new okhttp3.Request.Builder()
                .url("https://test.com/app")
                .addHeader("some-header", "some-value")
                .header("Proxy-Authorization", "pre-existing-value")
                .build();
        okhttp3.Response alreadyAuthenticatedResponse = new okhttp3.Response.Builder()
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .request(alreadyAuthenticatedRequest)
                .build();

        okhttp3.Request processedRequest = authenticator.authenticate(route, alreadyAuthenticatedResponse);
        assertThat(processedRequest, is(nullValue()));
    }

    @Test
    public void shouldUseCustomTelemetry() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().interceptors(), hasItem(isA(TelemetryInterceptor.class)));

        Telemetry currentTelemetry = null;
        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof TelemetryInterceptor) {
                TelemetryInterceptor interceptor = (TelemetryInterceptor) i;
                currentTelemetry = interceptor.getTelemetry();
            }
        }
        assertThat(currentTelemetry, is(notNullValue()));

        Telemetry newTelemetry = Mockito.mock(Telemetry.class);
        api.setTelemetry(newTelemetry);

        Telemetry updatedTelemetry = null;
        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof TelemetryInterceptor) {
                TelemetryInterceptor interceptor = (TelemetryInterceptor) i;
                updatedTelemetry = interceptor.getTelemetry();
            }
        }
        assertThat(updatedTelemetry, is(newTelemetry));
    }

    @Test
    public void shouldAddAndEnableTelemetryInterceptor() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().interceptors(), hasItem(isA(TelemetryInterceptor.class)));

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof TelemetryInterceptor) {
                TelemetryInterceptor telemetry = (TelemetryInterceptor) i;
                assertThat(telemetry.isEnabled(), is(true));
            }
        }
    }

    @Test
    public void shouldDisableTelemetryInterceptor() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().interceptors(), hasItem(isA(TelemetryInterceptor.class)));
        api.doNotSendTelemetry();

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof TelemetryInterceptor) {
                TelemetryInterceptor telemetry = (TelemetryInterceptor) i;
                assertThat(telemetry.isEnabled(), is(false));
            }
        }
    }

    @Test
    public void shouldAddAndDisableLoggingInterceptor() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().interceptors(), hasItem(isA(HttpLoggingInterceptor.class)));

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof HttpLoggingInterceptor) {
                HttpLoggingInterceptor logging = (HttpLoggingInterceptor) i;
                assertThat(logging.getLevel(), is(Level.NONE));
            }
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shouldEnableLoggingInterceptor() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().interceptors(), hasItem(isA(HttpLoggingInterceptor.class)));
        api.setLoggingEnabled(true);

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof HttpLoggingInterceptor) {
                HttpLoggingInterceptor logging = (HttpLoggingInterceptor) i;
                assertThat(logging.getLevel(), is(Level.BODY));
            }
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shouldDisableLoggingInterceptor() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().interceptors(), hasItem(isA(HttpLoggingInterceptor.class)));
        api.setLoggingEnabled(false);

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof HttpLoggingInterceptor) {
                HttpLoggingInterceptor logging = (HttpLoggingInterceptor) i;
                assertThat(logging.getLevel(), is(Level.NONE));
            }
        }
    }

    @Test
    public void shouldConfigureNoneLoggingFromOptions() {
        LoggingOptions loggingOptions = new LoggingOptions(LoggingOptions.LogLevel.NONE);
        HttpOptions options = new HttpOptions();
        options.setLoggingOptions(loggingOptions);

        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, options);
        assertThat(api.getClient().interceptors(), hasItem(isA(HttpLoggingInterceptor.class)));

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof HttpLoggingInterceptor) {
                HttpLoggingInterceptor logging = (HttpLoggingInterceptor) i;
                assertThat(logging.getLevel(), is(Level.NONE));
            }
        }
    }

    @Test
    public void shouldConfigureBasicLoggingFromOptions() {
        LoggingOptions loggingOptions = new LoggingOptions(LoggingOptions.LogLevel.BASIC);
        HttpOptions options = new HttpOptions();
        options.setLoggingOptions(loggingOptions);

        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, options);
        assertThat(api.getClient().interceptors(), hasItem(isA(HttpLoggingInterceptor.class)));

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof HttpLoggingInterceptor) {
                HttpLoggingInterceptor logging = (HttpLoggingInterceptor) i;
                assertThat(logging.getLevel(), is(Level.BASIC));
            }
        }
    }

    @Test
    public void shouldConfigureHeaderLoggingFromOptions() {
        LoggingOptions loggingOptions = new LoggingOptions(LoggingOptions.LogLevel.HEADERS);
        Set<String> headersToRedact = new HashSet<>();
        headersToRedact.add("Authorization");
        headersToRedact.add("Cookie");
        loggingOptions.setHeadersToRedact(headersToRedact);
        HttpOptions options = new HttpOptions();
        options.setLoggingOptions(loggingOptions);

        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, options);
        assertThat(api.getClient().interceptors(), hasItem(isA(HttpLoggingInterceptor.class)));

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof HttpLoggingInterceptor) {
                HttpLoggingInterceptor logging = (HttpLoggingInterceptor) i;
                assertThat(logging.getLevel(), is(Level.HEADERS));
            }
        }
    }

    @Test
    public void shouldConfigureBodyLoggingFromOptions() {
        LoggingOptions loggingOptions = new LoggingOptions(LoggingOptions.LogLevel.BODY);
        Set<String> headersToRedact = new HashSet<>();
        headersToRedact.add("Authorization");
        headersToRedact.add("Cookie");
        loggingOptions.setHeadersToRedact(headersToRedact);
        HttpOptions options = new HttpOptions();
        options.setLoggingOptions(loggingOptions);

        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, options);
        assertThat(api.getClient().interceptors(), hasItem(isA(HttpLoggingInterceptor.class)));

        for (Interceptor i : api.getClient().interceptors()) {
            if (i instanceof HttpLoggingInterceptor) {
                HttpLoggingInterceptor logging = (HttpLoggingInterceptor) i;
                assertThat(logging.getLevel(), is(Level.BODY));
            }
        }
    }

    @Test
    public void shouldUseDefaultMaxRequests() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().dispatcher().getMaxRequests(), is(64));
    }

    @Test
    public void shouldUseConfiguredMaxRequests() {
        HttpOptions options = new HttpOptions();
        options.setMaxRequests(10);
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, options);
        assertThat(api.getClient().dispatcher().getMaxRequests(), is(10));
    }

    @Test
    public void shouldThrowOnInValidMaxRequestsConfiguration() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("maxRequests must be one or greater.");

        HttpOptions options = new HttpOptions();
        options.setMaxRequests(0);
    }

    @Test
    public void shouldUseDefaultMaxRequestsPerHost() {
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(api.getClient().dispatcher().getMaxRequestsPerHost(), is(5));
    }

    @Test
    public void shouldUseConfiguredMaxRequestsPerHost() {
        HttpOptions options = new HttpOptions();
        options.setMaxRequestsPerHost(10);
        AuthAPI api = new AuthAPI(DOMAIN, CLIENT_ID, CLIENT_SECRET, options);
        assertThat(api.getClient().dispatcher().getMaxRequestsPerHost(), is(10));
    }

    @Test
    public void shouldThrowOnInValidMaxRequestsPerHostConfiguration() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("maxRequestsPerHost must be one or greater.");

        HttpOptions options = new HttpOptions();
        options.setMaxRequestsPerHost(0);
    }

    //Authorize

    @Test
    public void shouldThrowWhenAuthorizeUrlBuilderRedirectUriIsNull() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'redirect uri' must be a valid URL!");
        api.authorizeUrl(null);
    }

    @Test
    public void shouldThrowWhenAuthorizeUrlBuilderRedirectUriIsNotValidURL() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'redirect uri' must be a valid URL!");
        api.authorizeUrl("notvalid.url");
    }

    @Test
    public void shouldGetAuthorizeUrlBuilder() {
        AuthorizeUrlBuilder builder = api.authorizeUrl("https://domain.auth0.com/callback");
        assertThat(builder, is(notNullValue()));
    }

    @Test
    public void shouldSetAuthorizeUrlBuilderDefaultValues() {
        AuthAPI api = new AuthAPI("domain.auth0.com", CLIENT_ID, CLIENT_SECRET);
        String url = api.authorizeUrl("https://domain.auth0.com/callback").build();

        assertThat(url, isUrl("https", "domain.auth0.com", "/authorize"));
        assertThat(url, hasQueryParameter("response_type", "code"));
        assertThat(url, hasQueryParameter("client_id", CLIENT_ID));
        assertThat(url, hasQueryParameter("redirect_uri", "https://domain.auth0.com/callback"));
        assertThat(url, hasQueryParameter("connection", null));
    }


    //Logout

    @Test
    public void shouldThrowWhenLogoutUrlBuilderReturnToUrlIsNull() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'return to url' must be a valid URL!");
        api.logoutUrl(null, true);
    }

    @Test
    public void shouldThrowWhenLogoutUrlBuilderRedirectUriIsNotValidURL() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'return to url' must be a valid URL!");
        api.logoutUrl("notvalid.url", true);
    }

    @Test
    public void shouldGetLogoutUrlBuilder() {
        LogoutUrlBuilder builder = api.logoutUrl("https://domain.auth0.com/callback", true);
        assertThat(builder, is(notNullValue()));
    }

    @Test
    public void shouldSetLogoutUrlBuilderDefaultValues() {
        AuthAPI api = new AuthAPI("domain.auth0.com", CLIENT_ID, CLIENT_SECRET);
        String url = api.logoutUrl("https://my.domain.com/welcome", false).build();

        assertThat(url, isUrl("https", "domain.auth0.com", "/v2/logout"));
        assertThat(url, hasQueryParameter("client_id", null));
        assertThat(url, hasQueryParameter("returnTo", "https://my.domain.com/welcome"));
    }

    @Test
    public void shouldSetLogoutUrlBuilderDefaultValuesAndClientId() {
        AuthAPI api = new AuthAPI("domain.auth0.com", CLIENT_ID, CLIENT_SECRET);
        String url = api.logoutUrl("https://my.domain.com/welcome", true).build();

        assertThat(url, isUrl("https", "domain.auth0.com", "/v2/logout"));
        assertThat(url, hasQueryParameter("client_id", CLIENT_ID));
        assertThat(url, hasQueryParameter("returnTo", "https://my.domain.com/welcome"));
    }


    //UserInfo

    @Test
    public void shouldThrowOnUserInfoWithNullAccessToken() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'access token' cannot be null!");
        api.userInfo(null);
    }

    @Test
    public void shouldCreateUserInfoRequest() throws Exception {
        Request<UserInfo> request = api.userInfo("accessToken");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_USER_INFO, 200);
        UserInfo response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("GET", "/userinfo"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));
        assertThat(recordedRequest, hasHeader("Authorization", "Bearer accessToken"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getValues(), is(notNullValue()));
        assertThat(response.getValues(), hasEntry("email_verified", false));
        assertThat(response.getValues(), hasEntry("email", "test.account@userinfo.com"));
        assertThat(response.getValues(), hasEntry("clientID", "q2hnj2iu..."));
        assertThat(response.getValues(), hasEntry("updated_at", "2016-12-05T15:15:40.545Z"));
        assertThat(response.getValues(), hasEntry("name", "test.account@userinfo.com"));
        assertThat(response.getValues(), hasEntry("picture", "https://s.gravatar.com/avatar/dummy.png"));
        assertThat(response.getValues(), hasEntry("user_id", "auth0|58454..."));
        assertThat(response.getValues(), hasEntry("nickname", "test.account"));
        assertThat(response.getValues(), hasEntry("created_at", "2016-12-05T11:16:59.640Z"));
        assertThat(response.getValues(), hasEntry("sub", "auth0|58454..."));
        assertThat(response.getValues(), hasKey("identities"));
        List<Map<String, Object>> identities = (List<Map<String, Object>>) response.getValues().get("identities");
        assertThat(identities, hasSize(1));
        assertThat(identities.get(0), hasEntry("user_id", "58454..."));
        assertThat(identities.get(0), hasEntry("provider", "auth0"));
        assertThat(identities.get(0), hasEntry("connection", "Username-Password-Authentication"));
        assertThat(identities.get(0), hasEntry("isSocial", false));
    }


    //Reset Password

    @Test
    public void shouldThrowOnResetPasswordWithNullEmail() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'email' cannot be null!");
        api.resetPassword(null, "my-connection");
    }

    @Test
    public void shouldThrowOnResetPasswordWithNullConnection() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'connection' cannot be null!");
        api.resetPassword("me@auth0.com", null);
    }

    @Test
    public void shouldCreateResetPasswordRequest() throws Exception {
        Request<Void> request = api.resetPassword("me@auth0.com", "db-connection");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_RESET_PASSWORD, 200);
        Void response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/dbconnections/change_password"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("email", "me@auth0.com"));
        assertThat(body, hasEntry("connection", "db-connection"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, not(hasKey("password")));

        assertThat(response, is(nullValue()));
    }


    //Sign Up

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnSignUpWithNullEmail() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'email' cannot be null!");
        api.signUp(null, "p455w0rd", "my-connection");
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnSignUpWithNullPasswordString() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'password' cannot be null!");
        api.signUp("me@auth0.com", (String) null, "my-connection");
    }

    @Test
    public void shouldThrowOnSignUpWithNullPasswordCharArray() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'password' cannot be null!");
        api.signUp("me@auth0.com", (char[]) null, "my-connection");
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnSignUpWithNullConnection() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'connection' cannot be null!");
        api.signUp("me@auth0.com", "p455w0rd", null);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnUsernameSignUpWithNullEmail() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'email' cannot be null!");
        api.signUp(null, "me", "p455w0rd", "my-connection");
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnUsernameSignUpWithNullUsername() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'username' cannot be null!");
        api.signUp("me@auth0.com", null, "p455w0rd", "my-connection");
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnUsernameSignUpWithNullPasswordString() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'password' cannot be null!");
        api.signUp("me@auth0.com", "me", (String) null, "my-connection");
    }

    @Test
    public void shouldThrowOnUsernameSignUpWithNullPasswordCharArray() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'password' cannot be null!");
        api.signUp("me@auth0.com", "me", (char[]) null, "my-connection");
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnUsernameSignUpWithNullConnection() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'connection' cannot be null!");
        api.signUp("me@auth0.com", "me", "p455w0rd", null);
    }

    @Test
    public void shouldHaveNotStrongPasswordWithDetailedDescription() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FileReader fr = new FileReader(PASSWORD_STRENGTH_ERROR_RESPONSE_NONE);
        Map<String, Object> mapPayload = mapper.readValue(fr, new TypeReference<Map<String, Object>>() {
        });
        APIException ex = new APIException(mapPayload, 400);
        assertThat(ex.getError(), is("invalid_password"));
        String expectedDescription = "At least 10 characters in length; Contain at least 3 of the following 4 types of characters: lower case letters (a-z), upper case letters (A-Z), numbers (i.e. 0-9), special characters (e.g. !@#$%^&*); Should contain: lower case letters (a-z), upper case letters (A-Z), numbers (i.e. 0-9), special characters (e.g. !@#$%^&*); No more than 2 identical characters in a row (e.g., \"aaa\" not allowed)";
        assertThat(ex.getDescription(), is(expectedDescription));
    }

    @Test
    public void shouldHaveNotStrongPasswordWithShortDetailedDescription() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FileReader fr = new FileReader(PASSWORD_STRENGTH_ERROR_RESPONSE_SOME);
        Map<String, Object> mapPayload = mapper.readValue(fr, new TypeReference<Map<String, Object>>() {
        });
        APIException ex = new APIException(mapPayload, 400);
        assertThat(ex.getError(), is("invalid_password"));
        String expectedDescription = "Should contain: lower case letters (a-z), upper case letters (A-Z), numbers (i.e. 0-9), special characters (e.g. !@#$%^&*)";
        assertThat(ex.getDescription(), is(expectedDescription));
    }

    @Test
    public void shouldCreateSignUpRequestWithUsername() throws Exception {
        @SuppressWarnings("deprecation")
        SignUpRequest request = api.signUp("me@auth0.com", "me", "p455w0rd", "db-connection");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_SIGN_UP_USERNAME, 200);
        CreatedUser response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/dbconnections/signup"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("email", "me@auth0.com"));
        assertThat(body, hasEntry("username", "me"));
        assertThat(body, hasEntry("password", "p455w0rd"));
        assertThat(body, hasEntry("connection", "db-connection"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));

        assertThat(response, is(notNullValue()));
        assertThat(response.getUserId(), is("58457fe6b27"));
        assertThat(response.getEmail(), is("me@auth0.com"));
        assertThat(response.isEmailVerified(), is(false));
        assertThat(response.getUsername(), is("me"));
    }

    @Test
    public void shouldCreateSignUpRequest() throws Exception {
        @SuppressWarnings("deprecation")
        SignUpRequest request = api.signUp("me@auth0.com", "p455w0rd", "db-connection");

        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_SIGN_UP, 200);
        CreatedUser response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/dbconnections/signup"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("email", "me@auth0.com"));
        assertThat(body, hasEntry("password", "p455w0rd"));
        assertThat(body, hasEntry("connection", "db-connection"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, not(hasKey("username")));

        assertThat(response, is(notNullValue()));
        assertThat(response.getUserId(), is("58457fe6b27"));
        assertThat(response.getEmail(), is("me@auth0.com"));
        assertThat(response.isEmailVerified(), is(false));
        assertThat(response.getUsername(), is(nullValue()));
    }

    @Test
    public void shouldCreateSignUpRequestWithCustomParameters() throws Exception {
        @SuppressWarnings("deprecation")
        SignUpRequest request = api.signUp("me@auth0.com", "p455w0rd", "db-connection");
        assertThat(request, is(notNullValue()));
        Map<String, String> customFields = new HashMap<>();
        customFields.put("age", "25");
        customFields.put("address", "123, fake street");
        request.setCustomFields(customFields);

        server.jsonResponse(AUTH_SIGN_UP, 200);
        CreatedUser response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/dbconnections/signup"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("email", "me@auth0.com"));
        assertThat(body, hasEntry("password", "p455w0rd"));
        assertThat(body, hasEntry("connection", "db-connection"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasKey("user_metadata"));
        Map<String, String> metadata = (Map<String, String>) body.get("user_metadata");
        assertThat(metadata, hasEntry("age", "25"));
        assertThat(metadata, hasEntry("address", "123, fake street"));
        assertThat(body, not(hasKey("username")));

        assertThat(response, is(notNullValue()));
        assertThat(response.getUserId(), is("58457fe6b27"));
        assertThat(response.getEmail(), is("me@auth0.com"));
        assertThat(response.isEmailVerified(), is(false));
        assertThat(response.getUsername(), is(nullValue()));
    }


    //Log In with AuthorizationCode Grant

    @Test
    public void shouldThrowOnLogInWithAuthorizationCodeGrantAndRedirectUriWithNullCode() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'code' cannot be null!");
        api.exchangeCode(null, "https://domain.auth0.com/callback");
    }

    @Test
    public void shouldThrowOnLogInWithAuthorizationCodeGrantAndRedirectUriWithNullRedirectUri() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'redirect uri' cannot be null!");
        api.exchangeCode("code", null);
    }

    @Test
    public void shouldCreateLogInWithAuthorizationCodeGrantRequest() throws Exception {
        AuthRequest request = api.exchangeCode("code123", "https://domain.auth0.com/callback");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("code", "code123"));
        assertThat(body, hasEntry("redirect_uri", "https://domain.auth0.com/callback"));
        assertThat(body, hasEntry("grant_type", "authorization_code"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }

    @Test
    public void shouldCreateLogInWithAuthorizationCodeGrantRequestWithCustomParameters() throws Exception {
        AuthRequest request = api.exchangeCode("code123", "https://domain.auth0.com/callback");
        assertThat(request, is(notNullValue()));
        request.setAudience("https://myapi.auth0.com/users");
        request.setRealm("dbconnection");
        request.setScope("profile photos contacts");

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("code", "code123"));
        assertThat(body, hasEntry("redirect_uri", "https://domain.auth0.com/callback"));
        assertThat(body, hasEntry("grant_type", "authorization_code"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("audience", "https://myapi.auth0.com/users"));
        assertThat(body, hasEntry("realm", "dbconnection"));
        assertThat(body, hasEntry("scope", "profile photos contacts"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }


    //Log In with Password grant

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnLogInWithPasswordWithNullUsername() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'email or username' cannot be null!");
        api.login(null, "p455w0rd");
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnLogInWithPasswordWithNullPassword() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'password' cannot be null!");
        api.login("me", (String) null);
    }

    @Test
    public void shouldThrowOnLogInWithCharPasswordWithNullPassword() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'password' cannot be null!");
        api.login("me", (char[]) null);
    }

    @Test
    public void shouldCreateLogInWithPasswordGrantRequest() throws Exception {
        @SuppressWarnings("deprecation")
        AuthRequest request = api.login("me", "p455w0rd");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("grant_type", "password"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("username", "me"));
        assertThat(body, hasEntry("password", "p455w0rd"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }

    @Test
    public void shouldCreateLogInWithPasswordGrantRequestWithCustomParameters() throws Exception {
        @SuppressWarnings("deprecation")
        AuthRequest request = api.login("me", "p455w0rd");
        assertThat(request, is(notNullValue()));
        request.setRealm("dbconnection");
        request.setScope("profile photos contacts");
        request.setAudience("https://myapi.auth0.com/users");

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("grant_type", "password"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("username", "me"));
        assertThat(body, hasEntry("password", "p455w0rd"));
        assertThat(body, hasEntry("realm", "dbconnection"));
        assertThat(body, hasEntry("scope", "profile photos contacts"));
        assertThat(body, hasEntry("audience", "https://myapi.auth0.com/users"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }

    @Test
    public void shouldSetCustomHeaderWithPasswordlessRealmRequest() throws Exception {
        TokenRequest request = api.login("me", new char[]{'p','a','s','s','w','o','r','d'});
        request.addHeader("some-header", "some-value");

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));
        assertThat(recordedRequest, hasHeader("some-header", "some-value"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("grant_type", "password"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("username", "me"));
        assertThat(body, hasEntry("password", "password"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }

    //Log In with PasswordRealm grant

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnLogInWithPasswordRealmWithNullUsername() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'email or username' cannot be null!");
        api.login(null, "p455w0rd", "realm");
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnLogInWithPasswordRealmWithNullPasswordString() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'password' cannot be null!");
        api.login("me", (String) null, "realm");
    }

    @Test
    public void shouldThrowOnLogInWithPasswordRealmWithNullPasswordCharArray() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'password' cannot be null!");
        api.login("me", (char[]) null, "realm");
    }

    @SuppressWarnings("deprecation")
    @Test
    public void shouldThrowOnLogInWithPasswordRealmWithNullRealm() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'realm' cannot be null!");
        api.login("me", "p455w0rd", null);
    }

    @Test
    public void shouldCreateLogInWithPasswordRealmGrantRequest() throws Exception {
        @SuppressWarnings("deprecation")
        AuthRequest request = api.login("me", "p455w0rd", "realm");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("grant_type", "http://auth0.com/oauth/grant-type/password-realm"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("username", "me"));
        assertThat(body, hasEntry("password", "p455w0rd"));
        assertThat(body, hasEntry("realm", "realm"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }

    @Test
    public void shouldCreateLogInWithPasswordRealmGrantRequestWithCustomParameters() throws Exception {
        @SuppressWarnings("deprecation")
        AuthRequest request = api.login("me", "p455w0rd", "realm");
        assertThat(request, is(notNullValue()));
        request.setAudience("https://myapi.auth0.com/users");
        request.setRealm("dbconnection");
        request.setScope("profile photos contacts");

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("grant_type", "http://auth0.com/oauth/grant-type/password-realm"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("username", "me"));
        assertThat(body, hasEntry("password", "p455w0rd"));
        assertThat(body, hasEntry("audience", "https://myapi.auth0.com/users"));
        assertThat(body, hasEntry("realm", "dbconnection"));
        assertThat(body, hasEntry("scope", "profile photos contacts"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }


    //Log In with ClientCredentials grant

    @Test
    public void shouldThrowOnLogInWithClientCredentialsWithNullAudience() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'audience' cannot be null!");
        api.requestToken(null);
    }

    @Test
    public void shouldCreateLogInWithClientCredentialsGrantRequest() throws Exception {
        AuthRequest request = api.requestToken("https://myapi.auth0.com/users");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("grant_type", "client_credentials"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("audience", "https://myapi.auth0.com/users"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }

    // Login with Passwordless

    @Test
    public void shouldCreateStartEmailPasswordlessFlowRequest() throws Exception {
        Request<PasswordlessEmailResponse> request = api.startPasswordlessEmailFlow("user@domain.com",
                PasswordlessEmailType.CODE);
        assertThat(request, is(notNullValue()));

        server.jsonResponse(PASSWORDLESS_EMAIL_RESPONSE, 200);
        PasswordlessEmailResponse response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/passwordless/start"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("connection", "email"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("email", "user@domain.com"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getEmail(), not(emptyOrNullString()));
        assertThat(response.getId(), not(emptyOrNullString()));
        assertThat(response.isEmailVerified(), is(notNullValue()));
    }

    @Test
    public void startPasswordlessEmailFlowShouldThrowWhenEmailIsNull() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'email' cannot be null!");
        Request<PasswordlessEmailResponse> request = api.startPasswordlessEmailFlow(null, PasswordlessEmailType.CODE);
    }

    @Test
    public void startPasswordlessEmailFlowShouldThrowWhenTypeIsNull() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'type' cannot be null!");
        Request<PasswordlessEmailResponse> request = api.startPasswordlessEmailFlow("user@domain.com", null);
    }

    @Test
    public void shouldCreateStartEmailPasswordlessFlowRequestWithCustomParams() throws Exception {
        Map<String, String> authParams = new HashMap<>();
        authParams.put("scope", "openid profile email");
        authParams.put("state", "abc123");

        CustomRequest<PasswordlessEmailResponse> request = api.startPasswordlessEmailFlow("user@domain.com", PasswordlessEmailType.CODE)
                .addParameter("authParams", authParams);

        // verify that connection parameter can be overridden for custom connection types
        request.addParameter("connection", "custom-email");

        assertThat(request, is(notNullValue()));

        server.jsonResponse(PASSWORDLESS_EMAIL_RESPONSE, 200);
        PasswordlessEmailResponse response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/passwordless/start"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("connection", "custom-email"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("email", "user@domain.com"));
        assertThat(body, hasKey("authParams"));
        Map<String, String> authParamsSent = (Map<String, String>) body.get("authParams");
        assertThat(authParamsSent, hasEntry("scope", authParams.get("scope")));
        assertThat(authParamsSent, hasEntry("state", authParams.get("state")));

        assertThat(response, is(notNullValue()));
        assertThat(response.getEmail(), not(emptyOrNullString()));
        assertThat(response.getId(), not(emptyOrNullString()));
        assertThat(response.isEmailVerified(), is(notNullValue()));
    }

    @Test
    public void shouldCreateStartSmsPasswordlessFlowRequest() throws Exception {
        Request<PasswordlessSmsResponse> request = api.startPasswordlessSmsFlow("+16511234567");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(PASSWORDLESS_SMS_RESPONSE, 200);
        PasswordlessSmsResponse response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/passwordless/start"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("connection", "sms"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("phone_number", "+16511234567"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getPhoneNumber(), not(emptyOrNullString()));
        assertThat(response.getId(), not(emptyOrNullString()));
        assertThat(response.isPhoneVerified(), is(notNullValue()));
        assertThat(response.getRequestLanguage(), is(nullValue()));
    }

    @Test
    public void shouldCreateStartSmsPasswordlessFlowRequestWithCustomConnection() throws Exception {
        CustomRequest<PasswordlessSmsResponse> request = api.startPasswordlessSmsFlow("+16511234567");

        // verify that connection parameter can be overridden for custom connection types
        request.addParameter("connection", "custom-sms");

        assertThat(request, is(notNullValue()));

        server.jsonResponse(PASSWORDLESS_SMS_RESPONSE, 200);
        PasswordlessSmsResponse response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/passwordless/start"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("connection", "custom-sms"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("phone_number", "+16511234567"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getPhoneNumber(), not(emptyOrNullString()));
        assertThat(response.getId(), not(emptyOrNullString()));
        assertThat(response.isPhoneVerified(), is(notNullValue()));
        assertThat(response.getRequestLanguage(), is(nullValue()));
    }

    @Test
    public void startPasswordlessSmsFlowShouldThrowWhenPhoneIsNull() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'phoneNumber' cannot be null!");
        api.startPasswordlessSmsFlow(null);
    }

    @Test
    public void shouldCreateLoginWithPasswordlessCodeRequest() throws Exception {
        AuthRequest request = api.exchangePasswordlessOtp("+16511234567", "email", "otp".toCharArray());
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("realm", "email"));
        assertThat(body, hasEntry("grant_type", "http://auth0.com/oauth/grant-type/passwordless/otp"));
        assertThat(body, hasEntry("otp", "otp"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getScope(), is(nullValue()));
        assertThat(response.getAccessToken(), is(notNullValue()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
        assertThat(response.getIdToken(), is(notNullValue()));
        assertThat(response.getTokenType(), is(notNullValue()));
    }

    //Revoke a Token

    @Test
    public void shouldThrowOnRevokeTokenWithNullToken() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'refresh token' cannot be null!");
        api.revokeToken(null);
    }

    @Test
    public void shouldCreateRevokeTokenRequest() throws Exception {
        Request<Void> request = api.revokeToken("2679NfkaBn62e6w5E8zNEzjr");
        assertThat(request, is(notNullValue()));

        server.emptyResponse(200);
        Void response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/revoke"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("token", "2679NfkaBn62e6w5E8zNEzjr"));

        assertThat(response, is(nullValue()));
    }


    //Renew Authentication using Refresh Token

    @Test
    public void shouldThrowOnRenewAuthWithNullRefreshToken() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'refresh token' cannot be null!");
        api.renewAuth(null);
    }

    @Test
    public void shouldCreateRenewAuthRequest() throws Exception {
        AuthRequest request = api.renewAuth("ej2E8zNEzjrcSD2edjaE");
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("grant_type", "refresh_token"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("refresh_token", "ej2E8zNEzjrcSD2edjaE"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }

    // MFA grant

    @Test
    public void shouldThrowWhenExchangeMfaOtpCalledWithNullMfaToken() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'mfa token' cannot be null!");
        api.exchangeMfaOtp(null, new char[]{'o','t','p'});
    }

    @Test
    public void shouldThrowWhenExchangeMfaOtpCalledWithNullOtp() {
        exception.expect(IllegalArgumentException.class);
        exception.expectMessage("'otp' cannot be null!");
        api.exchangeMfaOtp("mfaToken", null);
    }

    @Test
    public void shouldCreateExchangeMfaOtpRequest() throws Exception {
        AuthRequest request = api.exchangeMfaOtp("mfaToken", new char[]{'o','t','p'});
        assertThat(request, is(notNullValue()));

        server.jsonResponse(AUTH_TOKENS, 200);
        TokenHolder response = request.execute();
        RecordedRequest recordedRequest = server.takeRequest();

        assertThat(recordedRequest, hasMethodAndPath("POST", "/oauth/token"));
        assertThat(recordedRequest, hasHeader("Content-Type", "application/json"));

        Map<String, Object> body = bodyFromRequest(recordedRequest);
        assertThat(body, hasEntry("grant_type", "http://auth0.com/oauth/grant-type/mfa-otp"));
        assertThat(body, hasEntry("client_id", CLIENT_ID));
        assertThat(body, hasEntry("client_secret", CLIENT_SECRET));
        assertThat(body, hasEntry("mfa_token", "mfaToken"));
        assertThat(body, hasEntry("otp", "otp"));

        assertThat(response, is(notNullValue()));
        assertThat(response.getAccessToken(), not(emptyOrNullString()));
        assertThat(response.getIdToken(), not(emptyOrNullString()));
        assertThat(response.getRefreshToken(), not(emptyOrNullString()));
        assertThat(response.getTokenType(), not(emptyOrNullString()));
        assertThat(response.getExpiresIn(), is(notNullValue()));
    }
}
