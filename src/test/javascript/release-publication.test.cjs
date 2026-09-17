const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const workflow = readFileSync(path.join(__dirname, '../../../.github/workflows/release.yml'), 'utf8');
const script = workflow.slice(workflow.lastIndexOf('        run: |') + '        run: |'.length)
    .split('\n').map(line => line.replace(/^          /, '')).join('\n');

for (const [tag, prerelease] of [['v1.0.0', false], ['v1.1.0-rc.1', true]]) {
    test(`publication marks ${tag} correctly without calling GitHub`, () => {
        const result = spawnSync('bash', ['-eu', '-c',
            'gh() { printf "%s\\n" "$@"; }\n' + script], {
            encoding: 'utf8', env: { ...process.env, RELEASE_TAG: tag }
        });
        assert.ifError(result.error);
        assert.equal(result.status, 0, result.stderr);
        const args = result.stdout.trim().split('\n');
        assert.deepEqual(args.slice(0, 3), ['release', 'create', tag]);
        assert.equal(args.includes('--prerelease'), prerelease);
        assert.equal(args.includes('--latest=false'), prerelease);
        assert.ok(args.includes('--verify-tag'));
    });
}
