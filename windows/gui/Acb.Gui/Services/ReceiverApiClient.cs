using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace Acb.Gui.Services;

public sealed class StartSessionOptions
{
    public string Transport { get; init; } = "usb-adb";
    public int Width { get; init; } = 1280;
    public int Height { get; init; } = 720;
    public int Fps { get; init; } = 30;
    public int Bitrate { get; init; } = 4_000_000;
    public int KeyInt { get; init; } = 60;
    public bool AudioEnabled { get; init; } = true;
    public int AudioSampleRate { get; init; } = 48_000;
    public int AudioChannels { get; init; } = 1;
    public int AudioBitrate { get; init; } = 96_000;
}

public class ReceiverApiClient
{
    private readonly HttpClient _http = new();
    private Uri _baseUri = new("http://127.0.0.1:39393");

    public void SetBaseAddress(string address)
    {
        if (string.IsNullOrWhiteSpace(address)) return;
        if (!Uri.TryCreate(address.Trim(), UriKind.Absolute, out var uri)) return;
        _baseUri = uri;
    }

    public async Task<string> GetDevicesAsync()
    {
        return await _http.GetStringAsync(BuildUri("/api/v1/devices"));
    }

    public async Task<string> SetupAdbAsync()
    {
        var resp = await _http.PostAsync(
            BuildUri("/api/v2/adb/setup"),
            new StringContent("{}", Encoding.UTF8, "application/json"));
        return await resp.Content.ReadAsStringAsync();
    }

    public async Task<string> StartV2SessionAsync(StartSessionOptions options)
    {
        var payload = new
        {
            transport = options.Transport,
            mode = "receiver_gui",
            video = new
            {
                codec = "h264",
                width = options.Width,
                height = options.Height,
                fps = options.Fps,
                bitrate = options.Bitrate,
                keyint = options.KeyInt
            },
            audio = new
            {
                codec = "aac",
                enabled = options.AudioEnabled,
                sampleRate = options.AudioSampleRate,
                channels = options.AudioChannels,
                bitrate = options.AudioBitrate
            }
        };
        var json = JsonSerializer.Serialize(payload);
        var resp = await _http.PostAsync(
            BuildUri("/api/v2/session/start"),
            new StringContent(json, Encoding.UTF8, "application/json"));
        return await resp.Content.ReadAsStringAsync();
    }

    public async Task<string> StopV2SessionAsync(string sessionId)
    {
        var payload = JsonSerializer.Serialize(new { sessionId });
        var resp = await _http.PostAsync(
            BuildUri("/api/v2/session/stop"),
            new StringContent(payload, Encoding.UTF8, "application/json"));
        return await resp.Content.ReadAsStringAsync();
    }

    public async Task<string> GetV2StatsAsync(string sessionId)
    {
        return await _http.GetStringAsync(BuildUri($"/api/v2/session/{sessionId}/stats"));
    }

    private Uri BuildUri(string path)
    {
        return new(_baseUri, path);
    }
}
