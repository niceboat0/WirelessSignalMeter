# Secure App Updates

## Current GitHub Update Entry

Settings > About > Check for updates performs a manual request to the latest
stable release at
`https://api.github.com/repos/niceboat0/WirelessSignalMeter/releases/latest`.
Startup no longer automatically checks the old update service. Drafts and
prereleases are excluded, and dotted version names are compared numerically.
A repository with no published stable release is reported separately from a
network error or an already installed version.

The user can open an uploaded ARM64 release APK in the browser, with a universal
APK as a fallback. Debug and unsigned APK names are excluded. The release page
and download link must both use HTTPS and belong to this exact GitHub
repository and release tag. If there is no compatible APK, the release page
remains available.

This GitHub entry opens the browser; it does not use the in-app downloader or
claim that the app has verified an APK's SHA-256, package name, or signing
certificate. Android performs its normal checks when the user installs the
download. The verification guarantees below apply only to the retained legacy
installer, which is not called by this manual GitHub entry.

## Legacy Verified Installer

The retained legacy implementation uses `https://rsrp.cn/nsrebuild`, trying `/api/version` and then
`/version.json`. Both metadata and APK requests require HTTPS with the platform's
normal certificate and hostname validation. Each redirect is checked again;
HTTP fallback is deliberately unavailable.

### Server Deployment

The server must serve a manifest like this over HTTPS:

```json
{
  "versionCode": 121002,
  "versionName": "1.2.1",
  "apkUrl": "/nsrebuild/releases/WirelessSignalMeter-1.2.1-arm64.apk",
  "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "forceUpdate": false,
  "title": "WirelessSignalMeter 1.2.1",
  "releaseNotes": ["Security fixes"],
  "publishDate": "2026-11-24"
}
```

Replace the example SHA-256 with the digest of the exact final, signed APK:

```powershell
(Get-FileHash -Algorithm SHA256 -LiteralPath '.\WirelessSignalMeter-1.2.1-arm64.apk').Hash.ToLowerInvariant()
```

`versionCode` is the APK's full Android version code, not the Gradle base version.
For example, base code `121` with arm64 ABI code `2` produces `121002`. The APK
code must exactly match the manifest and exceed the installed app's code.
The client uses 64-bit version codes and reads old ignored-version preferences
without requiring a reset.

Relative APK paths resolve against the service base directory using standard URL
rules. A leading slash resolves from the domain root; absolute HTTPS URLs are
also accepted. User information, fragments, HTTP links and HTTP redirect targets
are rejected. The `apkMd5` field from the old server is not a substitute for
`sha256`.

On November 24, 2026, the configured HTTPS manifest path returned 404. The legacy
port-9000 service returned HTTP URLs and MD5 metadata. Deploy the HTTPS route,
certificate, signed APK and SHA-256 metadata before relying on in-app updates.
The startup UI no longer invokes this legacy update channel. Its installer
remains available in source for a future integration with a verified manifest;
it never falls back to the insecure endpoint.

### Installation Boundary

Downloads stay in a randomly named file under the app's private
`cache/verified-updates/` directory. No publicly writable APK path or download
completion broadcast is used. The client enforces:

- At most five redirects, an 8-second connection timeout and a 15-second read
  timeout.
- A 1 MiB metadata limit and a 256 MiB APK limit, including responses with no
  content length. Metadata reads and APK copies also have total time limits.
- A mandatory, matching SHA-256 digest, and rejection of empty downloads.
- The installed app's package name, the exact advertised version, and the full
  set of current signer certificates. Empty or partial signer sets are rejected.

Signing-key rotation is intentionally not accepted by this first patch; it needs
a separate migration design. This change does not change the repository's
existing release signing configuration.

Only verified files are renamed to `.apk` and shared read-only through the
application-ID-specific FileProvider. Android's interactive installer still
validates APK signatures and asks the user to install. The app requests
unknown-source installation permission before downloading, if needed. The legacy
installer never installs silently or offers a shortcut around its verification
checks. After verification the prompt offers "Install now"; only that foreground
user action opens the installer, so background-activity restrictions cannot
silently consume the completed download. Failed downloads restore the prompt
for retry. A cancelled installer leaves the verified file available for another
attempt in the same app process.

This first patch uses an in-process download worker. If Android kills the
process, the next app launch can check again without the usual 24-hour throttle
and download again; downloads do not resume across process death. Failed partial
files are deleted, and files older than one day are cleaned up on a later update
attempt. A completed file is retained while the system installer may be reading
it.

The speed-node and log-upload features still use their existing legacy service
discovery. Their transport is outside this patch; changing the update channel
does not imply that all app traffic now uses HTTPS.
