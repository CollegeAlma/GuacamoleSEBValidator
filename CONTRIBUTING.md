# Contributing

Open a pull request against `main` with a description of the behavior change and
relevant tests. Keep real credentials, exam files, student data, build output,
and IDE settings out of commits. Report vulnerabilities through SECURITY.md.

Run these checks before submitting (Java 11+, Maven 3.8+, Node.js 22+, and Bash):

```sh
node --test src/test/javascript/*.test.cjs
mvn --batch-mode clean verify
```

Changes to authentication or the handshake should cover acceptance, rejection,
expiry, and selected/exempt group behavior. Real SEB integration results should
include the client version, operating system, and Guacamole provider setup.

Maintainers should update the version in pom.xml before tagging a release.
Release tags must exactly match `v` plus that version. Tags with a hyphen, such as
`v1.1.0-rc.1`, publish as prereleases; SNAPSHOT versions cannot be released.
Complete the README integration checklist before publishing a stable release.
