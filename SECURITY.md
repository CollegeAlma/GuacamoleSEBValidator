# Security policy

This extension is experimental pending the real-client integration checks in the
README. It validates SEB configuration during Guacamole authentication; it does
not continuously enforce an already-open remote desktop session. URL-based SEB
hashes are not replay-resistant. Deploy over HTTPS and protect Config Keys and
exam configuration files.

## Reporting a vulnerability

Please report suspected vulnerabilities privately through
[GitHub private vulnerability reporting](https://github.com/CollegeAlma/GuacamoleSEBValidator/security/advisories/new).
Do not open public issues containing exploit details, credentials, real Config
Keys, captured request hashes, authentication tokens, or student information.

Include the affected commit or release, Guacamole and SEB versions, relevant
authentication providers, and reproduction steps using sanitized test data.
Maintainers will investigate through the private advisory. No response-time
commitment is currently offered.

## Supported versions

Security fixes are developed on `main`. No stable release has yet been validated
against the documented Windows/macOS integration checklist. When releases become
available, use the latest release and review its notes for compatibility changes.
