# Guacamole Safe Exam Browser validator

Guacamole 1.6.0 extension that requires an approved Safe Exam Browser (SEB)
configuration before Guacamole authentication succeeds for selected user groups. It does not replace
the existing JDBC, LDAP, or other Guacamole authentication provider.

The extension supports:

- `X-SafeExamBrowser-ConfigKeyHash` for header-capable SEB clients.
- `SafeExamBrowser.security.configKey` for modern macOS/iOS WKWebView clients.
- Direct access to Guacamole without Nginx or another reverse proxy.

## Important security warning

Use HTTPS for deployment. Plain HTTP exposes the
Guacamole username, password, authentication token, remote-desktop stream, and
SEB validation values to any party able to capture or modify network traffic.
An isolated VLAN reduces exposure but does not provide encryption. HTTPS is
strongly recommended even when Guacamole is available only on a LAN.

This project is **experimental** until the manual integration checks below have
been completed for the target deployment. See [SECURITY.md](SECURITY.md) for its
security limitations and private reporting, [CONTRIBUTING.md](CONTRIBUTING.md)
for contributions, and [LICENSE](LICENSE) for the Apache 2.0 license.

## Requirements

- Apache Guacamole 1.6.0
- Tomcat 9 (the baseline servlet container for this build)
- Java 11 or newer
- Maven 3.8 or newer to build
- Direct URL such as `https://guacamole.example.org/guacamole/`

The example uses the standard HTTPS port 443. Include an explicit port when
using a nonstandard listener, such as `:8443`. The plugin does
not hardcode the IP address or port. For header validation, it uses the URL of
the request received directly by Tomcat. For JavaScript API validation, it
requires the submitted page URL to have the same scheme, host, port, and
Guacamole context path as the validation request.

## Configure SEB

For header-capable clients, enable:

```text
Exam > Use Browser & Config Keys (send in HTTP header)
```

For modern macOS/iOS clients:

```text
browserWindowWebView = 3
enableJavaScript = true
URLFilterEnableContentFilter = false
```

Use the exact Guacamole start URL, including any nonstandard port and the
trailing slash:

```text
https://guacamole.example.org/guacamole/
```

After all SEB settings are final, save the `.seb` file and copy the displayed
64-character **Config Key**.

## Configure Guacamole

Add the following to `/etc/guacamole/guacamole.properties`:

```properties
# Require SEB only for members of these Guacamole user groups.
seb-required-user-groups: students, exam-candidates

# One final SEB Config Key. Multiple keys may be comma-separated during a
# controlled configuration transition.
seb-config-keys: REPLACE_WITH_YOUR_64_CHARACTER_CONFIG_KEY

# How long a successful macOS JavaScript API handshake may be used to complete
# login. Allowed range: 30 through 3600 seconds. Default: 300.
seb-validation-max-age-seconds: 300
```

Replace `students, exam-candidates` with your actual user group identifiers.
Membership in any listed group requires SEB; users outside these groups can log
in normally without SEB. Identifiers are matched exactly (case-sensitive), with
whitespace around comma-separated entries ignored. Group identifiers containing
commas are not supported. Missing or blank `seb-required-user-groups` disables
SEB enforcement for all users, so existing installations must add this property
when upgrading. Restart Guacamole/Tomcat after changing the configuration.

Membership is taken from the authenticated user's effective groups supplied by
the authentication provider, including inherited groups if that provider reports
them. The provider must expose the groups you want to enforce through Guacamole's
[`getEffectiveUserGroups()` API](https://guacamole.apache.org/doc/guacamole-ext/org/apache/guacamole/net/auth/AuthenticatedUser.html#getEffectiveUserGroups()).
Groups only stored in another provider and absent from this set do not match.
These are **user groups**, not connection groups. An administrator who belongs
to a listed group also requires SEB.

Do not use the URL-specific value shown in an HTTP request as
`seb-config-keys`. Use the Config Key displayed by SEB Config Tool after saving
the final configuration.

## Build

JavaScript tests require Node.js 22 or newer and no npm dependencies. Both the
JavaScript and Maven tests run for branch pushes and pull requests, and again
in the GitHub release workflow before publishing.

```bash
cd guacamole-seb-validator
node --test src/test/javascript/*.test.cjs
mvn clean test package
```

The result is:

```text
target/guacamole-seb-validator-1.0.0.jar
```

Do not install a source archive as an extension. Only the JAR produced in the
`target` directory belongs in `/etc/guacamole/extensions`.

## Publish a GitHub release

The workflow in `.github/workflows/release.yml` builds and tests the project
when a tag beginning with `v` is pushed. After the tests pass, it creates a
GitHub release with the compiled JAR, `guacamole.properties.example`, and
automatically generated release notes. It uses GitHub's built-in token. Tags
containing a hyphen (for example, `v1.1.0-rc.1`) are marked as prereleases and
are not marked as the latest stable release.

Commit and push the workflow and project files first. Set the version in
`pom.xml` to the version you want to release and commit that change, then push
a matching tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The release workflow rejects tags that do not exactly match `v` followed by the
Maven project version, and rejects snapshot versions. The build job runs with
read-only repository access. Only the publication job has write permission; it
downloads the tested JAR and example properties from the build artifact. Actions
are pinned to commit SHAs with version comments.

Use a new version and tag for each release. Follow the workflow's progress in
the repository's **Actions** tab; downloads appear under **Releases**.

## Install on a native Guacamole/Tomcat installation

```bash
sudo install -d /etc/guacamole/extensions
sudo install -m 0644 target/guacamole-seb-validator-1.0.0.jar \
    /etc/guacamole/extensions/
sudo systemctl restart tomcat9
```

The service name may vary by installation. This extension uses `javax.servlet`.
Tomcat 10 uses Jakarta APIs and requires a separately migrated and tested
Guacamole application **and extension**; it is not supported by this unmodified
build.

## Install with Docker

Mount or copy the JAR into the container's Guacamole extensions directory,
normally `/etc/guacamole/extensions`, then restart the Guacamole container.

## Expected tests

For a user in one of the configured groups:

1. Open the start URL through the final `.seb` file and log in: accepted.
2. Open the same URL in Chrome, Safari, or Firefox and log in: rejected.
3. Change one SEB setting, save another `.seb` file, and log in: rejected.
4. On modern macOS SEB, confirm the browser console does not show a request
   error for `api/ext/seb-validation/validate`.
5. Check the Tomcat journal for `Safe Exam Browser Validator` and validation
   acceptance or rejection messages. The extension never logs the Config Key
   or received hash.

Also verify that a user outside the configured groups (including a user with
no groups) can log in through a regular browser, and that membership in any one
of multiple configured groups requires SEB.

## Troubleshooting

### JavaScript validation messages

The SEB banner now identifies the failure and suggests a recovery action:

| Failure | Message / action |
| --- | --- |
| Missing key API | This SEB version cannot provide its configuration key; use the version supplied for the exam. |
| Key retrieval exceeds 5 seconds | SEB did not return its key in time; reopen the exam configuration. |
| Missing or malformed hash | The returned hash must contain 64 hexadecimal characters; reopen the exam configuration. |
| Network failure | Cannot reach the validation server; check the connection. |
| Request exceeds 10 seconds, including response body | The validation server took too long; try again. |
| HTTP 401 | The validation request was not authorized; reload the Guacamole page. |
| HTTP 403 | Validation was rejected; reopen the approved configuration using the configured start URL. |
| HTTP 404 | The validation endpoint was not found; contact the administrator. |
| HTTP 5xx | The server could not complete validation; retry or contact the administrator. |
| Invalid JSON or missing `accepted: true` | Unexpected validation response; contact the administrator. |
| Other failures | Generic validation failure with the HTTP status when available. |

The handshake runs before login. HTTP 401 does not mean the username/password
is incorrect; Guacamole retains its existing login error messages. No SEB API
means no JavaScript warning, allowing ordinary browsers for exempt users and
header-based SEB clients. The server still enforces the configured groups.

Only one validation attempt runs at a time. The banner is updated in place and
cleared only after a valid JSON response containing `accepted: true` and an
integer `maxAgeSeconds` between 30 and 3600. Renewal is scheduled at half that
lifetime, capped at 60 seconds, measured from the request start. A 30-second
lifetime therefore renews after 15 seconds rather than 60. Failed attempts retry
after 15 seconds; returning to a visible tab triggers immediate validation.
Browser timer throttling or network outages can still delay renewal. Messages do not expose keys, hashes, raw
server responses, or unexpected exception details.

### All clients are rejected

- Confirm Guacamole is version 1.6.0.
- Confirm the final Config Key is in `guacamole.properties`.
- Confirm the `.seb` start URL exactly matches the direct Guacamole URL.
- Include the actual port (for example, `:8443`) if HTTPS uses a nonstandard port.
- Save the `.seb` configuration again and recopy the Config Key.

### macOS validation is rejected

- Require SEB 3.0 or newer; SEB 3.1 or newer is preferable.
- Set `browserWindowWebView=3`.
- Set `URLFilterEnableContentFilter=false`.
- Make sure JavaScript is enabled.
- Do not navigate to Guacamole through a second hostname if the start URL uses
  the IP address.

### Extension does not load

Check:

```bash
journalctl -u tomcat9 -n 200 --no-pager
```

The JAR manifest requires Guacamole 1.6.0. Change both `pom.xml` and
`guac-manifest.json` if building for another Guacamole release.

## Design notes

The header check calculates:

```text
SHA256(absolute request URL without fragment + Config Key)
```

The macOS JavaScript API already returns that URL-specific hash. The browser
posts the hash and page URL to the extension endpoint. The server verifies that
the page URL belongs to the directly accessed Guacamole origin and context,
validates the hash, and records a short-lived flag in the Tomcat HTTP session.
A rejected handshake or server-side validation/configuration error clears prior
SEB approval from the existing session without removing other session data or
creating a new session. Successful responses include `maxAgeSeconds` for browser
renewal scheduling. Update the JavaScript and server together when upgrading;
a cached older server response without this field is rejected by the new client.
The listener checks the authenticated user's effective group identifiers against
`seb-required-user-groups`. For matching users, it vetoes Guacamole's successful
authentication event unless either validation mechanism succeeded. Other users
bypass the SEB checks. The JavaScript handshake can still run before login for
SEB clients, when the user's identity and groups are not yet known.


## Manual integration checks before a stable release

Run these against Guacamole 1.6.0 / Tomcat 9 using real Windows SEB (header
validation) and macOS SEB (JavaScript API validation). Record the browser versions,
authentication providers, results, and relevant server logs. Automated tests use
mocked requests and do not establish real-browser or provider compatibility.

1. For each deployed JDBC/LDAP authentication setup, confirm the provider reports
   the selected effective user groups. Test a selected user, a user with multiple
   groups including a selected one, an exempt user, and a user with no groups.
2. Verify selected users are accepted with the approved configuration and rejected
   in an ordinary browser or with a different SEB configuration. Exempt users
   should log in normally. Exercise the header and JavaScript paths separately.
3. On the JavaScript path, submit login before the handshake completes: it must
   be rejected. Once the handshake succeeds, retry login: it must succeed.
4. After a valid handshake, submit an invalid one in the same HTTP session:
   expect HTTP 403 and loss of prior SEB approval. A subsequent selected-user
   login without a valid header must fail until another valid handshake occurs.
5. Set the lifetime to 30 seconds and verify renewals approximately every 15
   seconds while requests succeed. Test login around the 30-second boundary,
   network failure/recovery, and returning from a hidden tab. Stop renewal and
   confirm login fails after expiration. Repeat with the default 300 seconds.
6. Test login/logout and reauthentication with the actual provider combination,
   confirming that the browser sends the handshake's HTTP session cookie at login.
7. Push a test branch/PR to verify hosted CI. Before the first public release,
   confirm that a mismatched tag fails and that a matching non-snapshot tag builds
   and publishes the expected files. Release publication is an external action;
   local tests only exercise the version guard and workflow structure.

SEB enforcement occurs during authentication. A failed renewal does not itself
terminate an already-open Guacamole desktop or invalidate its authentication
token. This change does not add replay resistance to the URL-based SEB hash.
