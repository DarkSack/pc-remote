using PcRemote.Relay;

// Docker's HEALTHCHECK: the runtime image has no curl, so the relay checks itself.
if (args.Contains("--healthcheck"))
{
    using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };
    try { return (await http.GetStringAsync("http://127.0.0.1:8080/healthz")) == "ok" ? 0 : 1; }
    catch { return 1; }
}

var app = RelayServer.Build(Environment.GetEnvironmentVariable("RELAY_URLS") ?? "http://0.0.0.0:8080");
await app.RunAsync();
return 0;
