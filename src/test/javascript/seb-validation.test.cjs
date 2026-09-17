const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/seb-validation.js'), 'utf8');
const hash = 'A'.repeat(64);
const flush = () => new Promise(resolve => setImmediate(resolve));
const ok = () => ({ ok: true, json: async () => ({ accepted: true, maxAgeSeconds: 300 }) });

function harness(security = { configKey: hash }, fetchImpl = async () => ok()) {
    const notices = [];
    const timers = new Map();
    const calls = [];
    const events = {};
    let now = 0;
    let sequence = 0;
    const document = {
        visibilityState: 'visible', addEventListener(name, callback) { events[name] = callback; },
        readyState: 'complete', baseURI: 'https://exam.test/guacamole/',
        getElementById: () => notices[0],
        createElement: () => ({ style: {}, setAttribute(name, value) { this[name] = value; },
            remove() { notices.splice(notices.indexOf(this), 1); } }),
        body: { appendChild: notice => notices.push(notice) }
    };
    const window = {
        SafeExamBrowser: security === null ? undefined : { security },
        location: { href: 'https://exam.test/guacamole/#/login' },
        setTimeout(fn, ms) { const id = ++sequence; timers.set(id, { fn, ms }); return id; },
        clearTimeout(id) { timers.delete(id); },

    };
    vm.runInNewContext(source, { window, document, URL, URLSearchParams, AbortController, performance: { now: () => now },
        fetch: (...args) => { calls.push(args); return fetchImpl(...args); } });
    return { notices, timers, calls, window, document,
        advance: ms => { now += ms; },
        retry: () => events.visibilitychange(),
        fire(ms) {
            const entry = [...timers].find(([, timer]) => timer.ms === ms);
            assert.ok(entry, `Timer ${ms} exists`);
            timers.delete(entry[0]); return entry[1].fn();
        }
    };
}

for (const security of [null, undefined]) {
    test(`absent SEB API stays silent (${security})`, async () => {
        const h = harness(null);
        if (security === undefined) h.window.SafeExamBrowser = {};
        await h.retry();
        assert.equal(h.calls.length, 0);
        assert.equal(h.notices.length, 0);
    });
}

test('missing key API has actionable error', async () => {
    const h = harness({}); await flush();
    assert.match(h.notices[0].textContent, /cannot provide its configuration key/);
    assert.equal(h.notices[0].role, 'alert');
});

test('key timeout releases lock; late callback does not submit', async () => {
    let callback;
    const h = harness({ updateKeys(cb) { callback = cb; } });
    h.fire(5000); await flush(); callback(); await flush();
    assert.match(h.notices[0].textContent, /did not return.*in time/);
    assert.equal(h.calls.length, 0);
    h.window.SafeExamBrowser.security.configKey = hash;
    await h.retry(); assert.equal(h.notices.length, 0);
});

for (const key of ['', 'secret-invalid-key', 123, undefined]) {
    test(`rejects malformed/missing hash (${key})`, async () => {
        const h = harness({ configKey: key, updateKeys(cb) { cb(); } }); await flush();
        assert.match(h.notices[0].textContent, /must contain 64 hexadecimal characters/);
        assert.equal(h.calls.length, 0);
        assert.deepEqual([...h.timers.values()].map(t => t.ms), [15000]);
    });
}

test('unexpected SEB exception is sanitized and timer cleaned', async () => {
    const h = harness({ updateKeys() { throw new Error('secret-key'); } }); await flush();
    assert.match(h.notices[0].textContent, /^Safe Exam Browser validation failed/);
    assert.doesNotMatch(h.notices[0].textContent, /secret-key/);
    assert.deepEqual([...h.timers.values()].map(t => t.ms), [15000]);
});

for (const [status, expected] of [[401, /not authorized/], [403, /validation was rejected/],
    [404, /endpoint was not found/], [500, /server could not complete/],
    [503, /server could not complete/], [429, /HTTP 429/]]) {
    test(`HTTP ${status}`, async () => {
        const h = harness({ configKey: hash }, async () => ({ ok: false, status,
            json() { throw new Error('Must not read error response'); } }));
        await flush(); assert.match(h.notices[0].textContent, expected);
        assert.deepEqual([...h.timers.values()].map(t => t.ms), [15000]);
    });
}

test('network error is sanitized', async () => {
    const h = harness({ configKey: hash }, async () => { throw new Error('secret'); });
    await flush(); assert.match(h.notices[0].textContent, /Cannot reach/);
    assert.doesNotMatch(h.notices[0].textContent, /secret/);
    assert.deepEqual([...h.timers.values()].map(t => t.ms), [15000]);
});

for (const body of [null, {}, { accepted: false }, { accepted: 'true' }, '<html>secret</html>']) {
    test(`invalid successful response ${JSON.stringify(body)}`, async () => {
        const h = harness({ configKey: hash }, async () => ({ ok: true, json: async () => {
            if (typeof body === 'string') throw new SyntaxError(body);
            return body;
        } }));
        await flush(); assert.match(h.notices[0].textContent, /unexpected SEB validation response/);
        assert.doesNotMatch(h.notices[0].textContent, /secret/);
        assert.deepEqual([...h.timers.values()].map(t => t.ms), [15000]);
    });
}

for (const duringBody of [false, true]) {
    test(`request timeout during ${duringBody ? 'body' : 'fetch'} and overlap prevention`, async () => {
        const h = harness({ configKey: hash }, (_, { signal }) => {
            const pending = () => new Promise((resolve, reject) => {
                signal.addEventListener('abort', () => reject(new Error('aborted')));
            });
            return duringBody ? Promise.resolve({ ok: true, json: pending }) : pending();
        });
        await flush(); await h.retry();
        assert.equal(h.calls.length, 1);
        h.fire(10000); await flush();
        assert.match(h.notices[0].textContent, /took too long/);
        assert.deepEqual([...h.timers.values()].map(t => t.ms), [15000]);
    });
}

test('key retrieval cannot overlap', async () => {
    const h = harness({ updateKeys() {} });
    await h.retry(); assert.equal(h.timers.size, 1);
    h.fire(5000); await flush();
});

test('one banner updates and clears only on accepted true; request is preserved', async () => {
    let response = { ok: false, status: 403 };
    const h = harness({ configKey: hash }, async () => response);
    await flush(); const notice = h.notices[0];
    await h.retry(); assert.equal(h.notices.length, 1); assert.equal(h.notices[0], notice);
    response = { ok: true, json: async () => ({ accepted: false }) };
    await h.retry(); assert.equal(h.notices.length, 1);
    response = ok(); await h.retry(); assert.equal(h.notices.length, 0);
    const [url, options] = h.calls[0];
    assert.equal(url, 'https://exam.test/guacamole/api/ext/seb-validation/validate');
    assert.equal(options.credentials, 'same-origin');
    const body = new URLSearchParams(options.body);
    assert.equal(body.get('configKeyHash'), hash.toLowerCase());
    assert.equal(body.get('pageUrl'), 'https://exam.test/guacamole/');
    assert.deepEqual([...h.timers.values()].map(t => t.ms), [60000]);
});

for (const [lifetime, interval] of [[30, 15000], [60, 30000], [300, 60000], [3600, 60000]]) {
    test(`renewal uses lifetime ${lifetime} and accounts for request time`, async () => {
        let finish;
        const h = harness({ configKey: hash }, () => new Promise(resolve => { finish = resolve; }));
        await flush(); h.advance(4000);
        finish({ ok: true, json: async () => ({ accepted: true, maxAgeSeconds: lifetime }) });
        await flush();
        assert.deepEqual([...h.timers.values()].map(t => t.ms), [interval - 4000]);
    });
}

for (const lifetime of [undefined, null, '30', 29, 3601, 30.5]) {
    test(`invalid lifetime ${lifetime} preserves banner and schedules retry`, async () => {
        const h = harness({ configKey: hash }, async () => ({ ok: true,
            json: async () => ({ accepted: true, maxAgeSeconds: lifetime }) }));
        await flush(); assert.match(h.notices[0].textContent, /unexpected/);
        assert.deepEqual([...h.timers.values()].map(t => t.ms), [15000]);
    });
}

test('failed handshake retries automatically and recovers', async () => {
    let success = false;
    const h = harness({ configKey: hash }, async () => success ? ok() : { ok: false, status: 403 });
    await flush(); success = true;
    await h.fire(15000);
    assert.equal(h.calls.length, 2);
    assert.equal(h.notices.length, 0);
    assert.deepEqual([...h.timers.values()].map(t => t.ms), [60000]);
    await h.fire(60000);
    assert.equal(h.calls.length, 3);
    assert.equal(h.timers.size, 1);
});

test('visibility return replaces renewal timer; hiding does not validate', async () => {
    const h = harness(); await flush();
    h.document.visibilityState = 'hidden'; await h.retry();
    assert.equal(h.calls.length, 1);
    h.document.visibilityState = 'visible'; await h.retry();
    assert.equal(h.calls.length, 2);
    assert.deepEqual([...h.timers.values()].map(t => t.ms), [60000]);
});
