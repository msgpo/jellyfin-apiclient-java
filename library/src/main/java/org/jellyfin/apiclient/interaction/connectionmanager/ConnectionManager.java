package org.jellyfin.apiclient.interaction.connectionmanager;

import org.jellyfin.apiclient.interaction.ApiClient;
import org.jellyfin.apiclient.interaction.ApiEventListener;
import org.jellyfin.apiclient.interaction.ConnectionResult;
import org.jellyfin.apiclient.interaction.EmptyResponse;
import org.jellyfin.apiclient.interaction.IConnectionManager;
import org.jellyfin.apiclient.interaction.Response;
import org.jellyfin.apiclient.interaction.SerializedResponse;
import org.jellyfin.apiclient.interaction.device.IDevice;
import org.jellyfin.apiclient.interaction.http.HttpHeaders;
import org.jellyfin.apiclient.interaction.http.HttpRequest;
import org.jellyfin.apiclient.interaction.http.IAsyncHttpClient;
import org.jellyfin.apiclient.logging.ILogger;
import org.jellyfin.apiclient.model.apiclient.ConnectionOptions;
import org.jellyfin.apiclient.model.apiclient.ConnectionState;
import org.jellyfin.apiclient.model.apiclient.ServerCredentials;
import org.jellyfin.apiclient.model.apiclient.ServerInfo;
import org.jellyfin.apiclient.model.dto.UserDto;
import org.jellyfin.apiclient.model.session.ClientCapabilities;
import org.jellyfin.apiclient.model.system.PublicSystemInfo;
import org.jellyfin.apiclient.model.users.AuthenticationResult;
import org.jellyfin.apiclient.serialization.GsonJsonSerializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.regex.Pattern;

public class ConnectionManager implements IConnectionManager {
    protected ILogger logger;
    protected IAsyncHttpClient httpClient;

    private HashMap<String, ApiClient> apiClients = new HashMap<>();
    protected GsonJsonSerializer jsonSerializer;

    protected String applicationName;
    protected String applicationVersion;
    protected IDevice device;
    protected ClientCapabilities clientCapabilities;
    protected ApiEventListener apiEventListener;

    public ConnectionManager(ILogger logger,
                             IAsyncHttpClient httpClient,
                             String applicationName,
                             String applicationVersion,
                             IDevice device,
                             ClientCapabilities clientCapabilities,
                             ApiEventListener apiEventListener) {

        this.logger = logger;
        this.httpClient = httpClient;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
        this.device = device;
        this.clientCapabilities = clientCapabilities;
        this.apiEventListener = apiEventListener;

        this.jsonSerializer = new GsonJsonSerializer();
    }

    public ClientCapabilities getClientCapabilities() {
        return clientCapabilities;
    }
    @Override
    public IDevice getDevice() {
        return this.device;
    }

    void OnFailedConnection(Response<ConnectionResult> response) {
        logger.debug("No server available");
        ConnectionResult result = new ConnectionResult();
        result.setState(ConnectionState.Unavailable);
        response.onResponse(result);
    }

    void OnFailedConnection(Response<ConnectionResult> response, ArrayList<ServerInfo> servers) {
        logger.debug("No saved authentication");
        ConnectionResult result = new ConnectionResult();
        result.setState(ConnectionState.ServerSelection);
        result.setServers(new ArrayList<>());
        response.onResponse(result);
    }

    @Override
    public void Connect(final Response<ConnectionResult> response) {
        logger.debug("Entering initial connection workflow");
        GetAvailableServers(new GetAvailableServersResponse(logger, this, response));
    }

    void Connect(final ArrayList<ServerInfo> servers, final Response<ConnectionResult> response) {
        // Sort by last date accessed, descending
        Collections.sort(servers, new ServerInfoDateComparator());
        Collections.reverse(servers);

        if (servers.size() == 1) {
            Connect(servers.get(0), new ConnectionOptions(), new ConnectToSingleServerListResponse(response));
            return;
        }

        // Check the first server for a saved access token
        if (servers.size() == 0 || tangible.DotNetToJavaStringHelper.isNullOrEmpty(servers.get(0).getAccessToken())) {
            OnFailedConnection(response, servers);
            return;
        }

        ServerInfo firstServer = servers.get(0);
        Connect(firstServer, new ConnectionOptions(), new FirstServerConnectResponse(this, servers, response));
    }

    @Override
    public void Connect(final ServerInfo server, final Response<ConnectionResult> response) {
        Connect(server, new ConnectionOptions(), response);
    }

    @Override
    public void Connect(final ServerInfo server,
                        ConnectionOptions options,
                        final Response<ConnectionResult> response) {
        final String address = server.getAddress();
        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(address)) {
            OnFailedConnection(response);
            return;
        }

        TryConnect(address, new TryConnectResponse(this, server, options, logger, response));
    }

    void OnSuccessfulConnection(final ServerInfo server,
                                final PublicSystemInfo systemInfo,
                                final ConnectionOptions connectionOptions,
                                final Response<ConnectionResult> response) {

        if (systemInfo == null) {
            throw new IllegalArgumentException();
        }

        AfterConnectValidated(server, systemInfo, true, connectionOptions, response);
    }

    void AfterConnectValidated(final ServerInfo server,
                               final PublicSystemInfo systemInfo,
                               boolean verifyLocalAuthentication,
                               final ConnectionOptions options,
                               final Response<ConnectionResult> response) {
        if (verifyLocalAuthentication && !tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken())) {
            ValidateAuthentication(server, new AfterConnectValidatedResponse(this, server, systemInfo, options, response));
            return;
        }

        server.ImportInfo(systemInfo);

        if (options.getUpdateDateLastAccessed()) {
            server.setDateLastAccessed(new Date());
        }

        ConnectionResult result = new ConnectionResult();
        result.setApiClient(GetOrAddApiClient(server));
        result.setState(tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken()) ?
                ConnectionState.ServerSignIn :
                ConnectionState.SignedIn);

        result.getServers().add(server);
        result.getApiClient().EnableAutomaticNetworking(server);

        if (result.getState() == ConnectionState.SignedIn) {
            AfterConnected(result.getApiClient(), options);
        }

        response.onResponse(result);
    }

    private void ConnectMultiple(final String[] addresses, final int current, final Response<ConnectionResult> innerResponse) {
        Response<ConnectionResult> response = new Response<ConnectionResult>() {
            @Override
            public void onResponse(ConnectionResult result) {
                if (result.getState() == ConnectionState.Unavailable) {
                    int next = current + 1;
                    if (addresses.length > next) {
                        ConnectMultiple(addresses, next, innerResponse);
                        return;
                    }
                }

                innerResponse.onResponse(result);
            }

            @Override
            public void onError(Exception exception) {
                innerResponse.onError(exception);
            }
        };

        String address = addresses[current];

        logger.debug("Attempting to connect to server at %s", address);
        ServerInfo server = new ServerInfo();
        server.setAddress(address);
        Connect(server, new ConnectionOptions(), response);
    }

    @Override
    public void Connect(final String address, final Response<ConnectionResult> response) {
        final String[] normalizedAddresses = NormalizeAddress(address);
        ConnectMultiple(normalizedAddresses, 0, response);
    }

    private void ValidateAuthentication(final ServerInfo server, final EmptyResponse response) {
        final String url = server.getAddress();

        HttpHeaders headers = new HttpHeaders();
        headers.SetAccessToken(server.getAccessToken());

        final HttpRequest request = new HttpRequest();
        request.setUrl(url + "/system/info?format=json");
        request.setMethod("GET");
        request.setRequestHeaders(headers);

        Response<String> stringResponse = new ValidateAuthenticationResponse(this, jsonSerializer, server, response, request, httpClient, url);

        httpClient.Send(request, stringResponse);
    }

    void TryConnect(String url, final Response<PublicSystemInfo> response) {
        url += "/system/info/public?format=json";

        HttpRequest request = new HttpRequest();
        request.setUrl(url);
        request.setMethod("GET");
        request.setTimeout(8000); // 8 seconds

        httpClient.Send(request, new SerializedResponse<>(response, jsonSerializer, PublicSystemInfo.class));
    }

    protected ApiClient InstantiateApiClient(String serverAddress) {
        return new ApiClient(httpClient,
                logger,
                serverAddress,
                applicationName,
                applicationVersion,
                device,
                apiEventListener);
    }

    private ApiClient GetOrAddApiClient(ServerInfo server) {
        ApiClient apiClient = apiClients.get(server.getId());
        if (apiClient == null) {
            String address = server.getAddress();
            apiClient = InstantiateApiClient(address);
            apiClients.put(server.getId(), apiClient);
            apiClient.getAuthenticatedObservable().addObserver(new AuthenticatedObserver(this, apiClient));
        }

        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken())) {
            apiClient.ClearAuthenticationInfo();
        } else {
            apiClient.SetAuthenticationInfo(server.getAccessToken(), server.getUserId());
        }

        return apiClient;
    }

    void AfterConnected(ApiClient apiClient, ConnectionOptions options) {
        if (options.getReportCapabilities()) {
            apiClient.ReportCapabilities(clientCapabilities, new EmptyResponse());
        }

        if (options.getEnableWebSocket()) {
            apiClient.ensureWebSocket();
        }
    }

    void OnAuthenticated(final ApiClient apiClient,
                         final AuthenticationResult result,
                         ConnectionOptions options,
                         final boolean saveCredentials) {
        logger.debug("Updating credentials after local authentication");

        ServerInfo server = apiClient.getServerInfo();

        if (options.getUpdateDateLastAccessed()) {
            server.setDateLastAccessed(new Date());
        }

        if (saveCredentials) {
            server.setUserId(result.getUser().getId());
            server.setAccessToken(result.getAccessToken());
        } else {
            server.setUserId(null);
            server.setAccessToken(null);
        }

        AfterConnected(apiClient, options);
        OnLocalUserSignIn(result.getUser());
    }

    void OnLocalUserSignIn(UserDto user) {
        // TODO: Fire event
    }

    void OnLocalUserSignout(ApiClient apiClient) {
        // TODO: Fire event
    }

    /**
     * @deprecated Use new discovery class
     */
    @Deprecated
    public void GetAvailableServers(final Response<ArrayList<ServerInfo>> response) {
        response.onError(new Exception("Deprecated function"));
    }

    void OnGetServerResponse(ServerCredentials credentials,
                             ArrayList<ServerInfo> foundServers,
                             Response<ArrayList<ServerInfo>> response) {
        for (ServerInfo newServer : foundServers) {
            credentials.AddOrUpdateServer(newServer);
        }

        ArrayList<ServerInfo> servers = credentials.getServers();

        // Sort by last date accessed, descending
        Collections.sort(servers, new ServerInfoDateComparator());
        Collections.reverse(servers);

        credentials.setServers(servers);

        ArrayList<ServerInfo> clone = new ArrayList<>();
        clone.addAll(credentials.getServers());

        response.onResponse(clone);
    }

    public String[] NormalizeAddress(String address) throws IllegalArgumentException {
        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(address)) {
            throw new IllegalArgumentException("address");
        }

        // Better be safe
        address = address.trim();

        boolean protocolFound = Pattern.compile("^https?://.*$", Pattern.CASE_INSENSITIVE).matcher(address).matches();
        if (protocolFound) return new String[]{address};

        // Extra things like the default port could be added as options to this list

        return new String[]{
                "https://" + address,
                "http://" + address
        };
    }
}
