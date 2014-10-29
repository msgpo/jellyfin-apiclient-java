package MediaBrowser.ApiInteraction;

import MediaBrowser.ApiInteraction.Device.IDevice;
import MediaBrowser.ApiInteraction.Discovery.IServerLocator;
import MediaBrowser.ApiInteraction.Network.INetworkConnection;
import MediaBrowser.Model.ApiClient.*;
import MediaBrowser.Model.Connect.*;
import MediaBrowser.Model.Dto.BaseItemDto;
import MediaBrowser.Model.Dto.IHasServerId;
import MediaBrowser.Model.Dto.UserDto;
import MediaBrowser.Model.Extensions.StringHelper;
import MediaBrowser.Model.Logging.ILogger;
import MediaBrowser.Model.Serialization.IJsonSerializer;
import MediaBrowser.Model.Session.ClientCapabilities;
import MediaBrowser.Model.System.PublicSystemInfo;
import MediaBrowser.Model.System.SystemInfo;
import MediaBrowser.Model.Users.AuthenticationResult;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ConnectionManager implements IConnectionManager {

    private ICredentialProvider _credentialProvider;
    private INetworkConnection _networkConnectivity;
    private ILogger _logger;
    private IServerLocator _serverDiscovery;
    private IAsyncHttpClient _httpClient;

    private HashMap<String, ApiClient> apiClients = new HashMap<String, ApiClient>();
    private IJsonSerializer jsonSerializer;

    private String applicationName;
    private String applicationVersion;
    private IDevice device;
    private ClientCapabilities clientCapabilities;
    private ApiEventListener apiEventListener;

    private ConnectService connectService;
    private ConnectUser connectUser;

    public ConnectionManager(ICredentialProvider credentialProvider,
                             INetworkConnection networkConnectivity,
                             IJsonSerializer jsonSerializer,
                             ILogger logger,
                             IServerLocator serverDiscovery,
                             IAsyncHttpClient httpClient,
                             String applicationName,
                             String applicationVersion,
                             IDevice device,
                             ClientCapabilities clientCapabilities,
                             ApiEventListener apiEventListener) {

        _credentialProvider = credentialProvider;
        _networkConnectivity = networkConnectivity;
        _logger = logger;
        _serverDiscovery = serverDiscovery;
        _httpClient = httpClient;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
        this.device = device;
        this.clientCapabilities = clientCapabilities;
        this.apiEventListener = apiEventListener;
        this.jsonSerializer = jsonSerializer;

        connectService = new ConnectService(jsonSerializer, logger, httpClient);

        device.getResumeFromSleepObservable().addObserver(new GenericObserver() {

            @Override
            public void update(Observable observable, Object o)
            {
                WakeAllServers();
            }

        });
    }

    @Override
    public ApiClient GetApiClient(IHasServerId item) {

        return GetApiClient(item.getServerId());
    }

    @Override
    public ApiClient GetApiClient(String serverId) {

        return apiClients.get(serverId);
    }

    private void OnConnectUserSignIn(ConnectUser user){
        connectUser = user;
    }

    private void OnFailedConnection(Response<ConnectionResult> response){

        _logger.Debug("No server available");

        ConnectionResult result = new ConnectionResult();
        result.setState(ConnectionState.Unavailable);
        result.setConnectUser(connectUser);
        response.onResponse(result);
    }

    private void OnFailedConnection(Response<ConnectionResult> response, ArrayList<ServerInfo> servers){

        _logger.Debug("No server available");

        ConnectionResult result = new ConnectionResult();

        if (servers.size() == 0 && connectUser == null){
            result.setState(ConnectionState.ConnectSignIn);
        }
        else{
            result.setState(ConnectionState.ServerSelection);
        }

        result.setServers(new ArrayList<ServerInfo>());
        result.getServers().addAll(servers);

        result.setConnectUser(connectUser);

        response.onResponse(result);
    }

    @Override
    public void Connect(final Response<ConnectionResult> response) {

         _logger.Debug("Entering initial connection workflow");

         GetAvailableServers(new Response<ArrayList<ServerInfo>>(){

                @Override
                public void onResponse(ArrayList<ServerInfo> servers) {

                    _logger.Debug("Looping through server list");
                    Connect(servers, response);
                }
        });
    }

    private void Connect(ArrayList<ServerInfo> servers, final Response<ConnectionResult> response){

        Collections.sort(servers, new Comparator<ServerInfo>() {
            @Override
            public int compare(ServerInfo p1, ServerInfo p2) {
                // Descending
                return p2.getDateLastAccessed().compareTo(p1.getDateLastAccessed());
            }
        });

        if (servers.size() == 0){

            OnFailedConnection(response, servers);
            return;
        }

        if (servers.size() == 1)
        {
            Connect(servers.get(0), new Response<ConnectionResult>() {

                @Override
                public void onResponse(ConnectionResult result) {

                    if (result.getState() == ConnectionState.Unavailable) {
                        result.setState((result.getConnectUser() == null ? ConnectionState.ConnectSignIn : ConnectionState.ServerSelection));
                    }
                    response.onResponse(result);
                }
            });
            return;
        }

        ConnectToServerAtListIndex(servers, 0, response);
    }

    private void ConnectToServerAtListIndex(final ArrayList<ServerInfo> servers,
                                            final int index,
                                            final Response<ConnectionResult> response){

        Response<ConnectionResult> innerResponse = new Response<ConnectionResult>(){

            private void TryNextServer() {

                int nextIndex = index + 1;
                if (nextIndex < servers.size()) {

                    _logger.Debug("Trying next server");
                    ConnectToServerAtListIndex(servers, nextIndex, response);

                } else {

                    // No connection is available
                    OnFailedConnection(response, servers);
                }
            }

            @Override
            public void onResponse(ConnectionResult result) {

                if (result.getState() == ConnectionState.SignedIn) {
                    _logger.Debug("Connected to server");
                    response.onResponse(result);

                } else {
                    TryNextServer();
                }
            }

            @Override
            public void onError() {

                TryNextServer();
            }
        };

        ServerInfo server = servers.get(index);

        // Try to connect if there's a saved access token
        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken()) ||
                (connectUser != null && !tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken())))
        {
            Connect(server, true, innerResponse);
        }
        else{
            innerResponse.onError();
        }
    }

    @Override
    public void Connect(final ServerInfo server,
                        final Response<ConnectionResult> response) {

        Connect(server, true, response);
    }

    private void Connect(final ServerInfo server,
                        final boolean enableWakeOnLan,
                        final Response<ConnectionResult> response) {

        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getLocalAddress()))
        {
            TryConnect(server.getLocalAddress(), new Response<PublicSystemInfo>(){

                @Override
                public void onResponse(PublicSystemInfo result) {

                    ConnectToFoundServer(server, result, ConnectionMode.Local, true, response);
                }

                @Override
                public void onError() {

                    // Wake on lan and try again
                    if (enableWakeOnLan && server.getWakeOnLanInfos().size() > 0)
                    {
                        WakeServer(server, new EmptyResponse() {

                            @Override
                            public void onResponse() {

                                // Try local connection again
                                Connect(server, false, response);
                            }

                            @Override
                            public void onError() {

                                // No local connection available
                                TryConnectToRemoteAddress(server, response);
                            }
                        });

                        return;
                    }

                    // No local connection available
                    TryConnectToRemoteAddress(server, response);
                }

            });

            return;
        }

        TryConnectToRemoteAddress(server, response);
    }

    private void TryConnectToRemoteAddress(final ServerInfo server,
                                           final Response<ConnectionResult> response){

        // If local connection is unavailable, try to connect to the remote address
        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getRemoteAddress()))
        {
            TryConnect(server.getRemoteAddress(), new Response<PublicSystemInfo>(){

                @Override
                public void onResponse(PublicSystemInfo result) {

                    ConnectToFoundServer(server, result, ConnectionMode.Remote, true, response);
                }

                @Override
                public void onError() {

                    // Unable to connect
                    OnFailedConnection(response);
                }
            });
        }
        else{
            OnFailedConnection(response);
        }
    }

    private void ConnectToFoundServer(final ServerInfo server,
                                     final PublicSystemInfo systemInfo,
                                     final ConnectionMode connectionMode,
                                     boolean verifyAuthentication,
                                     final Response<ConnectionResult> response) {

        if (verifyAuthentication && !tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken()))
        {
            ValidateAuthentication(server, connectionMode, new EmptyResponse(){

                @Override
                public void onResponse() {

                    ConnectToFoundServer(server, systemInfo, connectionMode, false, response);
                }

                @Override
                public void onError() {

                    response.onError();
                }
            });

            return;
        }

        ServerCredentials credentials = _credentialProvider.GetCredentials();

        server.ImportInfo(systemInfo);
        server.setDateLastAccessed(new Date());
        credentials.AddOrUpdateServer(server);
        _credentialProvider.SaveCredentials(credentials);

        ConnectionResult result = new ConnectionResult();

        result.setApiClient(GetOrAddApiClient(server, connectionMode));
        result.setState(tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken()) ?
                ConnectionState.ServerSignIn :
                ConnectionState.SignedIn);

        if (result.getState() == ConnectionState.SignedIn)
        {
            EnsureWebSocketIfConfigured(result.getApiClient());
        }

        result.getServers().add(server);
        result.getApiClient().EnableAutomaticNetworking(server, connectionMode, _networkConnectivity);

        response.onResponse(result);
    }

    @Override
    public void Connect(String address, final Response<ConnectionResult> response) {

        address = NormalizeAddress(address);

        _logger.Debug("Attempting to connect to server at %s", address);

        final String finalAddress = address;
        TryConnect(address, new Response<PublicSystemInfo>(){

            @Override
            public void onResponse(PublicSystemInfo result) {

                ServerInfo server = new ServerInfo();
                server.setLocalAddress(finalAddress);

                Connect(server, response);
            }

            @Override
            public void onError() {

                OnFailedConnection(response);
            }

        });
    }

    @Override
    public void Logout(final Response<ConnectionResult> response) {

        _logger.Debug("Logging out of all servers");

        LogoutAll(new EmptyResponse() {

            private void OnSuccessOrFail() {

                _logger.Debug("Updating saved credentials for all servers");
                ServerCredentials credentials = _credentialProvider.GetCredentials();

                for (ServerInfo server : credentials.getServers()) {

                    server.setAccessToken(null);
                    server.setUserId(null);
                }

                _credentialProvider.SaveCredentials(credentials);

                Connect(response);
            }

            @Override
            public void onResponse() {
                OnSuccessOrFail();
            }

            @Override
            public void onError() {
                OnSuccessOrFail();
            }
        });
    }

    private Observable connectedObservable = new Observable();
    @Override
    public Observable getConnectedObservable() {
        return connectedObservable;
    }

    private Observable remoteLoggedOutObservable = new Observable();
    @Override
    public Observable getRemoteLoggedOutObservable() {
        return remoteLoggedOutObservable;
    }

    private void ValidateAuthentication(final ServerInfo server, ConnectionMode connectionMode, final EmptyResponse response)
    {
        final String url = connectionMode == ConnectionMode.Local ? server.getLocalAddress() : server.getRemoteAddress();

        HttpHeaders headers = new HttpHeaders();
        headers.SetAccessToken(server.getAccessToken());

        final HttpRequest request = new HttpRequest();
        request.setUrl(url + "/mediabrowser/system/info?format=json");
        request.setMethod("GET");
        request.setRequestHeaders(headers);

        Response<String> stringResponse = new Response<String>(){

            @Override
            public void onResponse(String jsonResponse) {

                SystemInfo obj = jsonSerializer.DeserializeFromString(jsonResponse, SystemInfo.class);
                server.ImportInfo(obj);

                if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getUserId()))
                {
                    request.setUrl(url + "/mediabrowser/users/" + server.getUserId() + "?format=json");

                    _httpClient.Send(request, new Response<String>(){

                        @Override
                        public void onResponse(String stringResponse) {

                            UserDto user = jsonSerializer.DeserializeFromString(stringResponse, UserDto.class);
                            OnLocalUserSignIn(user);
                            response.onResponse();
                        }
                        @Override
                        public void onError() {

                            server.setUserId(null);
                            server.setAccessToken(null);
                            response.onResponse();
                        }
                    });
                }
                else {
                    response.onResponse();
                }
            }

            @Override
            public void onError() {

                server.setUserId(null);
                server.setAccessToken(null);
                response.onResponse();
            }
        };

        _httpClient.Send(request, stringResponse);
    }

    private void TryConnect(String url, final Response<PublicSystemInfo> response)
    {
        url += "/mediabrowser/system/info/public?format=json";

        HttpRequest request = new HttpRequest();
        request.setUrl(url);
        request.setMethod("GET");

        Response<String> stringResponse = new Response<String>(){

            @Override
            public void onResponse(String jsonResponse) {

                PublicSystemInfo obj = jsonSerializer.DeserializeFromString(jsonResponse, PublicSystemInfo.class);

                response.onResponse(obj);
            }

            @Override
            public void onError() {

                response.onError();
            }
        };

        _httpClient.Send(request, stringResponse);
    }

    private ApiClient GetOrAddApiClient(ServerInfo server, ConnectionMode connectionMode)
    {
        ApiClient apiClient = apiClients.get(server.getId());

        if (apiClient == null){

            String address = connectionMode == ConnectionMode.Local ?
                    server.getLocalAddress() :
                    server.getRemoteAddress();

            apiClient = new ApiClient(_httpClient,
                    _logger,
                    address,
                    applicationName,
                    device,
                    applicationVersion,
                    apiEventListener,
                    clientCapabilities);

            apiClients.put(server.getId(), apiClient);

            final ApiClient finalApiClient = apiClient;

            apiClient.getAuthenticatedObservable().addObserver(new GenericObserver() {

                @Override
                public void update(Observable observable, Object o)
                {
                    OnAuthenticated(finalApiClient, (AuthenticationResult) o, true);
                }

            });
        }

        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(server.getAccessToken()))
        {
            apiClient.ClearAuthenticationInfo();
        }
        else
        {
            apiClient.SetAuthenticationInfo(server.getAccessToken(), server.getUserId());
        }

        return apiClient;
    }

    private void EnsureWebSocketIfConfigured(ApiClient apiClient)
    {
        apiClient.OpenWebSocket();
    }

    private void OnAuthenticated(final ApiClient apiClient,
                                 final AuthenticationResult result,
                                 final boolean saveCredentials)
    {
        _logger.Debug("Updating credentials after local authentication");

        apiClient.GetSystemInfoAsync(new Response<SystemInfo>() {

            @Override
            public void onResponse(SystemInfo info) {

                ServerInfo server = apiClient.getServerInfo();
                server.ImportInfo(info);

                ServerCredentials credentials = _credentialProvider.GetCredentials();

                server.setDateLastAccessed(new Date());

                if (saveCredentials)
                {
                    server.setUserId(result.getUser().getId());
                    server.setAccessToken(result.getAccessToken());
                }
                else {
                    server.setUserId(null);
                    server.setAccessToken(null);
                }

                credentials.AddOrUpdateServer(server);
                _credentialProvider.SaveCredentials(credentials);

                EnsureWebSocketIfConfigured(apiClient);

                OnLocalUserSignIn(result.getUser());
            }
        });
    }

    private void OnLocalUserSignIn(UserDto user)
    {

    }

    private void GetAvailableServers(final Response<ArrayList<ServerInfo>> response)
    {
        NetworkStatus networkInfo = _networkConnectivity.getNetworkStatus();

        _logger.Debug("Getting saved servers via credential provider");
        final ServerCredentials credentials = _credentialProvider.GetCredentials();

        final int numTasks = 2;
        final int[] numTasksCompleted = {0};

        if (networkInfo.GetIsLocalNetworkAvailable())
        {
            _logger.Debug("Scanning network for local servers");

            FindServers(new Response<ArrayList<ServerInfo>>(){

                private void OnAny(ArrayList<ServerInfo> foundServers){

                    synchronized (credentials){

                        numTasksCompleted[0]++;

                        OnGetServerResponse(credentials, foundServers, response, numTasksCompleted[0] >= numTasks);
                    }
                }

                @Override
                public void onResponse(ArrayList<ServerInfo> response) {
                    OnAny(response);
                }

                @Override
                public void onError() {

                    OnAny(new ArrayList<ServerInfo>());
                }

            });
        }
        else {
            numTasksCompleted[0]++;
        }

        _logger.Debug("Getting server list from Connect");

        if (!tangible.DotNetToJavaStringHelper.isNullOrEmpty(credentials.getConnectAccessToken()))
        {
            EnsureConnectUser(credentials, new EmptyResponse(){

                @Override
                public void onResponse() {

                    connectService.GetServers(credentials.getConnectUserId(), credentials.getConnectAccessToken(), new Response<ConnectUserServer[]>(){

                        private void OnAny(ConnectUserServer[] foundServers){

                            synchronized (credentials){

                                numTasksCompleted[0]++;

                                OnGetServerResponse(credentials, ConvertServerList(foundServers), response, numTasksCompleted[0] >= numTasks);
                            }
                        }

                        @Override
                        public void onResponse(ConnectUserServer[] response) {

                            OnAny(response);
                        }

                        @Override
                        public void onError() {

                            OnAny(new ConnectUserServer[]{});
                        }
                    });
                }
            });
        }
        else{
            numTasksCompleted[0]++;
        }
    }

    private void EnsureConnectUser(ServerCredentials credentials, final EmptyResponse response){

        if (connectUser != null && StringHelper.EqualsIgnoreCase(connectUser.getId(), credentials.getConnectUserId()))
        {
            response.onResponse();
            return;
        }

        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(credentials.getConnectUserId()) || tangible.DotNetToJavaStringHelper.isNullOrEmpty(credentials.getConnectAccessToken()))
        {
            response.onResponse();
            return;
        }

        ConnectUserQuery query = new ConnectUserQuery();

        connectService.GetConnectUser(query, credentials.getConnectAccessToken(), new Response<ConnectUser>(){

            @Override
            public void onResponse(ConnectUser user) {

                OnConnectUserSignIn(user);
                response.onResponse();
            }

            @Override
            public void onError() {

                response.onResponse();
            }


        });
    }

    private void OnGetServerResponse(ServerCredentials credentials,
                                     ArrayList<ServerInfo> foundServers,
                                     Response<ArrayList<ServerInfo>> response,
                                     boolean isComplete){

        for(ServerInfo newServer : foundServers){

            credentials.AddOrUpdateServer(newServer);
        }

        if (isComplete){
            _credentialProvider.SaveCredentials(credentials);

            ArrayList<ServerInfo> clone = new ArrayList<ServerInfo>();
            clone.addAll(credentials.getServers());
            response.onResponse(clone);
        }
    }

    private ArrayList<ServerInfo> ConvertServerList(ConnectUserServer[] userServers){

        ArrayList<ServerInfo> servers = new ArrayList<ServerInfo>();

        for(ConnectUserServer userServer : userServers){

            ServerInfo server = new ServerInfo();

            server.setExchangeToken(userServer.getAccessKey());
            server.setId(userServer.getSystemId());
            server.setName(userServer.getName());
            server.setLocalAddress(userServer.getLocalAddress());
            server.setRemoteAddress(userServer.getUrl());

            servers.add(server);
        }

        return servers;
    }

    private void FindServers(final Response<ArrayList<ServerInfo>> response)
    {
        _serverDiscovery.FindServers(new Response<ServerDiscoveryInfo[]>(){

            @Override
            public void onResponse(ServerDiscoveryInfo[] foundServers) {

                ArrayList<ServerInfo> servers = new ArrayList<ServerInfo>();

                for (int i=0; i< foundServers.length; i++) {

                    ServerInfo server = new ServerInfo();

                    server.setId(foundServers[i].getId());
                    server.setLocalAddress(foundServers[i].getAddress());
                    server.setName(foundServers[i].getName());

                    servers.add(server);
                }

                response.onResponse(servers);
            }

            @Override
            public void onError() {

                ArrayList<ServerInfo> servers = new ArrayList<ServerInfo>();

                response.onResponse(servers);
            }
        });
    }

    private void WakeAllServers()
    {
        _logger.Debug("Waking all servers");

        for(ServerInfo server : _credentialProvider.GetCredentials().getServers()){

            WakeServer(server, new EmptyResponse());
        }
    }

    private void WakeServer(ServerInfo info, final EmptyResponse response)
    {
        _logger.Debug("Waking server: %s, Id: %s", info.getName(), info.getId());

        ArrayList<WakeOnLanInfo> wakeList = info.getWakeOnLanInfos();

        final int count = wakeList.size();

        if (count == 0){
            _logger.Debug("Server %s has no saved wake on lan profiles", info.getName());
            response.onResponse();
            return;
        }

        final ArrayList<EmptyResponse> doneList = new ArrayList<EmptyResponse>();

        for(WakeOnLanInfo wakeOnLanInfo : wakeList){

            WakeServer(info, new EmptyResponse(){

                private void OnServerDone(){
                    synchronized(doneList) {

                        doneList.add(new EmptyResponse());

                        if (doneList.size() >= count){
                            response.onResponse();
                        }
                    }
                }

                @Override
                public void onResponse() {

                    OnServerDone();
                }

                @Override
                public void onError() {

                    OnServerDone();
                }
            });
        }
    }

    private void WakeServer(WakeOnLanInfo info, EmptyResponse response) throws IOException {

        _networkConnectivity.SendWakeOnLan(info.getMacAddress(), info.getPort(), response);
    }

    private String NormalizeAddress(String address) throws IllegalArgumentException {

        if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(address))
        {
            throw new IllegalArgumentException("address");
        }

        if (StringHelper.IndexOfIgnoreCase(address, "http") == -1)
        {
            address = "http://" + address;
        }

        return address;
    }

    private void LogoutAll(final EmptyResponse response){

        Object[] clientList = apiClients.values().toArray();

        final int count = clientList.length;

        if (count == 0){
            response.onResponse();
            return;
        }

        final ArrayList<EmptyResponse> doneList = new ArrayList<EmptyResponse>();

        for(Object clientObj : clientList){

            ApiClient client = (ApiClient)clientObj;

            if (tangible.DotNetToJavaStringHelper.isNullOrEmpty(client.getAccessToken()))
            {
                synchronized (doneList) {

                    doneList.add(new EmptyResponse());

                    if (doneList.size() >= count) {
                        response.onResponse();
                    }
                }
            }

            client.Logout(new EmptyResponse() {

                @Override
                public void onResponse() {

                    synchronized (doneList) {

                        doneList.add(new EmptyResponse());

                        if (doneList.size() >= count) {
                            response.onResponse();
                        }

                    }

                }

                @Override
                public void onError() {

                    onResponse();
                }

            });
        }
    }

    public void LoginToConnect(String username, String password, final EmptyResponse response) throws UnsupportedEncodingException, NoSuchAlgorithmException {

        connectService.Authenticate(username, password, new Response<ConnectAuthenticationResult>() {

            @Override
            public void onResponse(ConnectAuthenticationResult result) {

                ServerCredentials credentials = _credentialProvider.GetCredentials();

                credentials.setConnectAccessToken(result.getAccessToken());
                credentials.setConnectUserId(result.getUser().getId());

                _credentialProvider.SaveCredentials(credentials);

                OnConnectUserSignIn(result.getUser());

                response.onResponse();
            }

            @Override
            public void onError() {

                response.onError();
            }
        });
    }

    public void CreatePin(String deviceId, Response<PinCreationResult> response)
    {
        connectService.CreatePin(deviceId, response);
    }

    public void GetPinStatus(PinCreationResult pin, Response<PinStatusResult> response)
    {
        connectService.GetPinStatus(pin, response);
    }

    public void ExchangePin(PinCreationResult pin, final Response<PinExchangeResult> response)
    {
        connectService.ExchangePin(pin, new Response<PinExchangeResult>(){

            @Override
            public void onResponse(PinExchangeResult result) {

                ServerCredentials credentials = _credentialProvider.GetCredentials();

                credentials.setConnectAccessToken(result.getAccessToken());
                credentials.setConnectUserId(result.getUserId());

                _credentialProvider.SaveCredentials(credentials);

                response.onResponse(result);
            }

            @Override
            public void onError() {

                response.onError();
            }

        });
    }
}
