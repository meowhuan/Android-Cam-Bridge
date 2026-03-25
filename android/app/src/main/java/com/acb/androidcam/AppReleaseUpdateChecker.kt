package com.acb.androidcam

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppReleaseVersionInfo(
    val tagName: String,
    val version: String,
    val htmlUrl: String,
    val isPrerelease: Boolean,
    val publishedAt: String?,
)

data class AppReleaseUpdateResult(
    val currentVersion: String,
    val includePreview: Boolean,
    val currentBuildIsPreview: Boolean,
    val updateAvailable: Boolean,
    val latestRelease: AppReleaseVersionInfo,
)

class AppReleaseUpdateChecker(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
) {
    fun checkForUpdates(currentVersion: String, includePreview: Boolean): AppReleaseUpdateResult {
        val request = Request.Builder()
            .url("$RELEASES_API_URL?per_page=12")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Android-Cam-Bridge-App")
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub releases returned HTTP ${response.code}")
            }

            response.body.string()
        }

        val releases = JSONArray(body)
        var latestRelease: AppReleaseVersionInfo? = null
        for (index in 0 until releases.length()) {
            val item = releases.getJSONObject(index)
            if (item.optBoolean("draft")) {
                continue
            }

            val isPrerelease = item.optBoolean("prerelease")
            if (!includePreview && isPrerelease) {
                continue
            }

            val tagName = item.optString("tag_name").trim()
            if (tagName.isBlank()) {
                continue
            }

            latestRelease = AppReleaseVersionInfo(
                tagName = tagName,
                version = normalizeVersion(tagName),
                htmlUrl = item.optString("html_url").ifBlank { RELEASES_URL },
                isPrerelease = isPrerelease,
                publishedAt = item.optString("published_at").ifBlank { null },
            )
            break
        }

        val release = latestRelease
            ?: throw IllegalStateException(
                if (includePreview) {
                    "No published releases were found."
                } else {
                    "No stable releases were found."
                },
            )

        val currentBuildIsPreview = SemanticVersion.parseOrNull(currentVersion)?.isPrerelease == true ||
            isPreviewBuildVersion(currentVersion)

        return AppReleaseUpdateResult(
            currentVersion = currentVersion,
            includePreview = includePreview,
            currentBuildIsPreview = currentBuildIsPreview,
            updateAvailable = isNewerVersionAvailable(currentVersion, release.version),
            latestRelease = release,
        )
    }

    private fun isNewerVersionAvailable(currentVersion: String, latestVersion: String): Boolean {
        val current = SemanticVersion.parseOrNull(currentVersion)
        val latest = SemanticVersion.parseOrNull(latestVersion)
        if (current != null && latest != null) {
            return latest > current
        }

        return normalizeVersion(currentVersion) != normalizeVersion(latestVersion)
    }

    private fun isPreviewBuildVersion(version: String): Boolean {
        val normalized = normalizeVersion(version)
        return normalized.contains("-preview", ignoreCase = true) ||
            normalized.contains("-alpha", ignoreCase = true) ||
            normalized.contains("-beta", ignoreCase = true) ||
            normalized.contains("-rc", ignoreCase = true)
    }

    private fun normalizeVersion(version: String): String {
        var text = version.trim()
        if (text.startsWith("v", ignoreCase = true)) {
            text = text.substring(1)
        }

        val plusIndex = text.indexOf('+')
        return if (plusIndex >= 0) text.substring(0, plusIndex) else text
    }

    private data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<String>,
    ) : Comparable<SemanticVersion> {
        val isPrerelease: Boolean
            get() = preRelease.isNotEmpty()

        override fun compareTo(other: SemanticVersion): Int {
            if (major != other.major) {
                return major.compareTo(other.major)
            }
            if (minor != other.minor) {
                return minor.compareTo(other.minor)
            }
            if (patch != other.patch) {
                return patch.compareTo(other.patch)
            }

            if (!isPrerelease && !other.isPrerelease) {
                return 0
            }
            if (!isPrerelease) {
                return 1
            }
            if (!other.isPrerelease) {
                return -1
            }

            val count = minOf(preRelease.size, other.preRelease.size)
            for (index in 0 until count) {
                val left = preRelease[index]
                val right = other.preRelease[index]
                val leftNumber = left.toIntOrNull()
                val rightNumber = right.toIntOrNull()

                if (leftNumber != null && rightNumber != null) {
                    if (leftNumber != rightNumber) {
                        return leftNumber.compareTo(rightNumber)
                    }
                    continue
                }

                if ((leftNumber != null) != (rightNumber != null)) {
                    return if (leftNumber != null) -1 else 1
                }

                val textCompare = left.compareTo(right, ignoreCase = true)
                if (textCompare != 0) {
                    return textCompare
                }
            }

            return preRelease.size.compareTo(other.preRelease.size)
        }

        companion object {
            fun parseOrNull(input: String?): SemanticVersion? {
                val raw = input?.trim().orEmpty()
                if (raw.isBlank()) {
                    return null
                }

                val normalized = (if (raw.startsWith("v", ignoreCase = true)) raw.substring(1) else raw)
                    .substringBefore('+')
                val dashIndex = normalized.indexOf('-')
                val core = if (dashIndex >= 0) normalized.substring(0, dashIndex) else normalized
                val prerelease = if (dashIndex >= 0) normalized.substring(dashIndex + 1) else ""

                val coreParts = core.split('.').filter { it.isNotBlank() }
                if (coreParts.size !in 2..3) {
                    return null
                }

                val major = coreParts.getOrNull(0)?.toIntOrNull() ?: return null
                val minor = coreParts.getOrNull(1)?.toIntOrNull() ?: return null
                val patch = coreParts.getOrNull(2)?.toIntOrNull() ?: 0
                val prereleaseParts = if (prerelease.isBlank()) {
                    emptyList()
                } else {
                    prerelease.split('.').filter { it.isNotBlank() }
                }

                return SemanticVersion(
                    major = major,
                    minor = minor,
                    patch = patch,
                    preRelease = prereleaseParts,
                )
            }
        }
    }

    companion object {
        const val REPOSITORY_URL = "https://github.com/meowhuan/Android-Cam-Bridge"
        const val RELEASES_URL = "$REPOSITORY_URL/releases"
        private const val RELEASES_API_URL = "https://api.github.com/repos/meowhuan/Android-Cam-Bridge/releases"
    }
}
