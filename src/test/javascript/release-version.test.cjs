const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const script = path.join(__dirname, '../../../.github/scripts/check-release-version.sh');

for (const [tag, version, accepted] of [
    ['v1.0.0', '1.0.0', true],
    ['v1.0.1', '1.0.0', false],
    ['v1.0.0-SNAPSHOT', '1.0.0-SNAPSHOT', false],
    ['v', '', false],
    ['1.0.0', '1.0.0', false]
]) {
    test(`release guard: ${tag} / ${version}`, () => {
        const result = spawnSync('bash', [script, tag, version], { encoding: 'utf8' });
        assert.ifError(result.error);
        assert.equal(result.status, accepted ? 0 : 1, result.stderr);
    });
}
