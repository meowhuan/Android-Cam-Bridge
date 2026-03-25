using System.Net;
using System.Net.Http;
using System.Text.Json;

namespace Acb.Gui.Services;

public sealed record class ReleaseVersionInfo(
    string TagName,
    string Version,
    string HtmlUrl,
    bool IsPrerelease,
    DateTimeOffset? PublishedAt);

public sealed record class ReleaseUpdateResult(
    string CurrentVersion,
    bool IncludePreview,
    bool CurrentBuildIsPreview,
    bool UpdateAvailable,
    ReleaseVersionInfo LatestRelease);

public sealed class ReleaseUpdateService
{
    public const string RepositoryUrl = "https://github.com/meowhuan/Android-Cam-Bridge";
    public const string ReleasesUrl = RepositoryUrl + "/releases";
    private const string ReleasesApiUrl = "https://api.github.com/repos/meowhuan/Android-Cam-Bridge/releases";

    private readonly HttpClient _http = new();

    public ReleaseUpdateService()
    {
        _http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("Acb.Gui release-checker");
        _http.Timeout = TimeSpan.FromSeconds(8);
    }

    public async Task<ReleaseUpdateResult> CheckForUpdatesAsync(string currentVersion, bool includePreview, CancellationToken cancellationToken = default)
    {
        var latestRelease = includePreview
            ? await GetLatestVisibleReleaseAsync(cancellationToken)
            : await GetLatestStableReleaseAsync(cancellationToken);

        var currentBuildIsPreview =
            (SemanticVersion.TryParse(currentVersion, out var current) && current.IsPrerelease) ||
            LooksLikePreRelease(currentVersion);

        return new ReleaseUpdateResult(
            CurrentVersion: currentVersion,
            IncludePreview: includePreview,
            CurrentBuildIsPreview: currentBuildIsPreview,
            UpdateAvailable: IsNewerVersionAvailable(currentVersion, latestRelease.Version),
            LatestRelease: latestRelease);
    }

    private async Task<ReleaseVersionInfo> GetLatestStableReleaseAsync(CancellationToken cancellationToken)
    {
        try
        {
            using var response = await _http.GetAsync($"{ReleasesApiUrl}/latest", cancellationToken);
            response.EnsureSuccessStatusCode();
            using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
            using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
            return ParseRelease(document.RootElement);
        }
        catch (HttpRequestException ex) when (ex.StatusCode == HttpStatusCode.NotFound)
        {
            return await GetLatestFromReleaseListAsync(includePrerelease: false, cancellationToken);
        }
    }

    private Task<ReleaseVersionInfo> GetLatestVisibleReleaseAsync(CancellationToken cancellationToken)
    {
        return GetLatestFromReleaseListAsync(includePrerelease: true, cancellationToken);
    }

    private async Task<ReleaseVersionInfo> GetLatestFromReleaseListAsync(bool includePrerelease, CancellationToken cancellationToken)
    {
        using var response = await _http.GetAsync($"{ReleasesApiUrl}?per_page=12", cancellationToken);
        response.EnsureSuccessStatusCode();
        using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
        if (document.RootElement.ValueKind != JsonValueKind.Array)
        {
            throw new InvalidOperationException("GitHub release response was not an array.");
        }

        foreach (var item in document.RootElement.EnumerateArray())
        {
            if (item.TryGetProperty("draft", out var draft) && draft.ValueKind == JsonValueKind.True)
            {
                continue;
            }

            var release = ParseRelease(item);
            if (!includePrerelease && release.IsPrerelease)
            {
                continue;
            }

            return release;
        }

        throw new InvalidOperationException(includePrerelease
            ? "No published releases were found in the repository."
            : "No stable releases were found in the repository.");
    }

    private static ReleaseVersionInfo ParseRelease(JsonElement element)
    {
        var tagName = element.TryGetProperty("tag_name", out var tag) ? tag.GetString()?.Trim() ?? string.Empty : string.Empty;
        var htmlUrl = element.TryGetProperty("html_url", out var url) ? url.GetString()?.Trim() ?? ReleasesUrl : ReleasesUrl;
        var isPrerelease = element.TryGetProperty("prerelease", out var prerelease) && prerelease.ValueKind == JsonValueKind.True;
        DateTimeOffset? publishedAt = null;

        if (element.TryGetProperty("published_at", out var published) &&
            published.ValueKind == JsonValueKind.String &&
            DateTimeOffset.TryParse(published.GetString(), out var parsedPublishedAt))
        {
            publishedAt = parsedPublishedAt;
        }

        if (string.IsNullOrWhiteSpace(tagName))
        {
            throw new InvalidOperationException("GitHub release tag_name was empty.");
        }

        return new ReleaseVersionInfo(
            TagName: tagName,
            Version: NormalizeVersion(tagName),
            HtmlUrl: string.IsNullOrWhiteSpace(htmlUrl) ? ReleasesUrl : htmlUrl,
            IsPrerelease: isPrerelease,
            PublishedAt: publishedAt);
    }

    private static bool IsNewerVersionAvailable(string currentVersion, string latestVersion)
    {
        if (SemanticVersion.TryParse(currentVersion, out var current) &&
            SemanticVersion.TryParse(latestVersion, out var latest))
        {
            return latest.CompareTo(current) > 0;
        }

        return !string.Equals(
            NormalizeVersion(currentVersion),
            NormalizeVersion(latestVersion),
            StringComparison.OrdinalIgnoreCase);
    }

    private static bool LooksLikePreRelease(string version)
    {
        var normalized = NormalizeVersion(version);
        return normalized.Contains("-preview", StringComparison.OrdinalIgnoreCase) ||
               normalized.Contains("-alpha", StringComparison.OrdinalIgnoreCase) ||
               normalized.Contains("-beta", StringComparison.OrdinalIgnoreCase) ||
               normalized.Contains("-rc", StringComparison.OrdinalIgnoreCase);
    }

    private static string NormalizeVersion(string value)
    {
        var text = value?.Trim() ?? string.Empty;
        if (text.StartsWith("v", StringComparison.OrdinalIgnoreCase))
        {
            text = text[1..];
        }

        var plusIndex = text.IndexOf('+');
        return plusIndex >= 0 ? text[..plusIndex] : text;
    }

    private readonly record struct SemanticVersion(int Major, int Minor, int Patch, string[] PreRelease) : IComparable<SemanticVersion>
    {
        public bool IsPrerelease => PreRelease.Length > 0;

        public int CompareTo(SemanticVersion other)
        {
            var majorCompare = Major.CompareTo(other.Major);
            if (majorCompare != 0)
            {
                return majorCompare;
            }

            var minorCompare = Minor.CompareTo(other.Minor);
            if (minorCompare != 0)
            {
                return minorCompare;
            }

            var patchCompare = Patch.CompareTo(other.Patch);
            if (patchCompare != 0)
            {
                return patchCompare;
            }

            if (!IsPrerelease && !other.IsPrerelease)
            {
                return 0;
            }

            if (!IsPrerelease)
            {
                return 1;
            }

            if (!other.IsPrerelease)
            {
                return -1;
            }

            var count = Math.Min(PreRelease.Length, other.PreRelease.Length);
            for (var index = 0; index < count; index++)
            {
                var left = PreRelease[index];
                var right = other.PreRelease[index];
                var leftIsNumber = int.TryParse(left, out var leftNumber);
                var rightIsNumber = int.TryParse(right, out var rightNumber);

                if (leftIsNumber && rightIsNumber)
                {
                    var numberCompare = leftNumber.CompareTo(rightNumber);
                    if (numberCompare != 0)
                    {
                        return numberCompare;
                    }

                    continue;
                }

                if (leftIsNumber != rightIsNumber)
                {
                    return leftIsNumber ? -1 : 1;
                }

                var textCompare = string.Compare(left, right, StringComparison.OrdinalIgnoreCase);
                if (textCompare != 0)
                {
                    return textCompare;
                }
            }

            return PreRelease.Length.CompareTo(other.PreRelease.Length);
        }

        public static bool TryParse(string? input, out SemanticVersion version)
        {
            version = default;

            var normalized = NormalizeVersion(input ?? string.Empty);
            if (string.IsNullOrWhiteSpace(normalized))
            {
                return false;
            }

            var dashIndex = normalized.IndexOf('-');
            var corePart = dashIndex >= 0 ? normalized[..dashIndex] : normalized;
            var prereleasePart = dashIndex >= 0 ? normalized[(dashIndex + 1)..] : string.Empty;

            var coreSegments = corePart.Split('.', StringSplitOptions.RemoveEmptyEntries);
            if (coreSegments.Length is < 2 or > 3)
            {
                return false;
            }

            if (!int.TryParse(coreSegments[0], out var major) ||
                !int.TryParse(coreSegments[1], out var minor))
            {
                return false;
            }

            var patch = 0;
            if (coreSegments.Length == 3 && !int.TryParse(coreSegments[2], out patch))
            {
                return false;
            }

            var preReleaseSegments = string.IsNullOrWhiteSpace(prereleasePart)
                ? Array.Empty<string>()
                : prereleasePart.Split('.', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

            version = new SemanticVersion(major, minor, patch, preReleaseSegments);
            return true;
        }
    }
}
