// Tests for the page-side bridge runtime (src/main/resources/assets/ferric_oxide/webui/bridge.js).
//
// The runtime is loaded into a fresh VM context per test with a fake `window.ipc`, so every
// outbound message is captured as the exact JSON string the Java side would receive, and inbound
// envelopes are fed in through the same `window.__ferric.accept` entry point Java evaluates.
//
// Run with: node --test src/test/js/bridge.test.js

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const RUNTIME_PATH = path.join(
    __dirname, '..', '..', 'main', 'resources', 'assets', 'ferric_oxide', 'webui', 'bridge.js');
const RESOURCE_BASE = 'ferric://';

/**
 * Loads the runtime into a fresh context, mirroring what Java does: substitute the resource base,
 * then install the script before any page code runs.
 *
 * @returns {{ferric: object, accept: Function, sent: string[], errors: string[]}}
 */
function loadBridge() {
    const source = fs.readFileSync(RUNTIME_PATH, 'utf8');
    assert.ok(source.includes('__RESOURCE_BASE__'), 'runtime must carry the resource-base placeholder');

    const sent = [];
    const errors = [];
    const sandbox = {
        window: {ipc: {postMessage: (json) => sent.push(json)}},
        console: {
            error: (...args) => errors.push(args.join(' ')),
            warn: (...args) => errors.push(args.join(' ')),
            log: () => {
            }
        }
    };
    vm.runInNewContext(source.replace('__RESOURCE_BASE__', RESOURCE_BASE), sandbox);

    return {
        ferric: sandbox.window.ferric,
        accept: sandbox.window.__ferric.accept,
        sent,
        errors
    };
}

/** The last outbound envelope, parsed. */
function lastSent(bridge) {
    assert.ok(bridge.sent.length > 0, 'expected an outbound message');
    return JSON.parse(bridge.sent[bridge.sent.length - 1]);
}

/**
 * Drains the microtask queue. Handler replies go out through promise callbacks, so a fixed
 * number of `await Promise.resolve()` hops is a guess; yielding to a macrotask is not.
 */
function flush() {
    return new Promise((resolve) => setImmediate(resolve));
}

test('runtime installs itself before any page script', () => {
    const bridge = loadBridge();

    assert.strictEqual(typeof bridge.ferric.emit, 'function');
    assert.strictEqual(typeof bridge.ferric.call, 'function');
    assert.strictEqual(typeof bridge.ferric.on, 'function');
    assert.strictEqual(typeof bridge.ferric.handle, 'function');
    assert.strictEqual(typeof bridge.ferric.resource, 'function');
});

test('emit sends an event envelope', () => {
    const bridge = loadBridge();

    bridge.ferric.emit('demo.ping', {count: 3});

    assert.deepStrictEqual(lastSent(bridge), {k: 'e', n: 'demo.ping', p: {count: 3}});
});

test('emit without a payload sends an explicit null', () => {
    const bridge = loadBridge();

    bridge.ferric.emit('demo.close');

    assert.deepStrictEqual(lastSent(bridge), {k: 'e', n: 'demo.close', p: null});
});

test('call sends a query and resolves with the reply payload', async () => {
    const bridge = loadBridge();

    const pending = bridge.ferric.call('demo.player', {detailed: true});
    const query = lastSent(bridge);
    assert.strictEqual(query.k, 'q');
    assert.strictEqual(query.n, 'demo.player');
    assert.deepStrictEqual(query.p, {detailed: true});
    assert.strictEqual(typeof query.i, 'number');

    bridge.accept({k: 'r', i: query.i, p: {name: 'Steve'}});

    assert.deepStrictEqual(await pending, {name: 'Steve'});
});

test('call rejects when Java replies with an error', async () => {
    const bridge = loadBridge();

    const pending = bridge.ferric.call('demo.player');
    bridge.accept({k: 'r', i: lastSent(bridge).i, e: 'no player in world'});

    await assert.rejects(pending, {message: 'no player in world'});
});

test('concurrent calls are correlated by their own ids', async () => {
    const bridge = loadBridge();

    const first = bridge.ferric.call('demo.a');
    const firstId = lastSent(bridge).i;
    const second = bridge.ferric.call('demo.b');
    const secondId = lastSent(bridge).i;
    assert.notStrictEqual(firstId, secondId);

    // Reply out of order.
    bridge.accept({k: 'r', i: secondId, p: 'second'});
    bridge.accept({k: 'r', i: firstId, p: 'first'});

    assert.strictEqual(await first, 'first');
    assert.strictEqual(await second, 'second');
});

test('on receives Java events', () => {
    const bridge = loadBridge();
    const seen = [];
    bridge.ferric.on('demo.game_time', (time) => seen.push(time.ticks));

    bridge.accept({k: 'e', n: 'demo.game_time', p: {ticks: 1200}});

    assert.deepStrictEqual(seen, [1200]);
});

test('on supports several listeners and returns an unsubscribe function', () => {
    const bridge = loadBridge();
    const seen = [];
    const off = bridge.ferric.on('demo.tick', () => seen.push('first'));
    bridge.ferric.on('demo.tick', () => seen.push('second'));

    bridge.accept({k: 'e', n: 'demo.tick'});
    assert.deepStrictEqual(seen, ['first', 'second']);

    off();
    bridge.accept({k: 'e', n: 'demo.tick'});
    assert.deepStrictEqual(seen, ['first', 'second', 'second']);
});

test('a throwing listener does not stop the remaining ones', () => {
    const bridge = loadBridge();
    const seen = [];
    bridge.ferric.on('demo.tick', () => {
        throw new Error('listener exploded');
    });
    bridge.ferric.on('demo.tick', () => seen.push('second'));

    bridge.accept({k: 'e', n: 'demo.tick'});

    assert.deepStrictEqual(seen, ['second']);
    assert.ok(bridge.errors.some((line) => line.includes('listener exploded')), bridge.errors.join('\n'));
});

test('handle answers a Java query', async () => {
    const bridge = loadBridge();
    bridge.ferric.handle('demo.form', (payload) => ({echo: payload.value * 2}));

    bridge.accept({k: 'q', i: 7, n: 'demo.form', p: {value: 21}});
    await flush();

    assert.deepStrictEqual(lastSent(bridge), {k: 'r', i: 7, p: {echo: 42}});
});

test('handle awaits a promise result', async () => {
    const bridge = loadBridge();
    let resolveHandler;
    bridge.ferric.handle('demo.slow', () => new Promise((resolve) => {
        resolveHandler = resolve;
    }));

    bridge.accept({k: 'q', i: 9, n: 'demo.slow'});
    assert.strictEqual(bridge.sent.length, 0, 'nothing is sent before the handler settles');

    resolveHandler('late');
    await flush();

    assert.deepStrictEqual(lastSent(bridge), {k: 'r', i: 9, p: 'late'});
});

test('a query with no handler gets an error reply', () => {
    const bridge = loadBridge();

    bridge.accept({k: 'q', i: 4, n: 'demo.missing'});

    assert.deepStrictEqual(lastSent(bridge), {k: 'r', i: 4, e: 'no handler: demo.missing'});
});

test('a throwing handler replies with an error instead of hanging Java', async () => {
    const bridge = loadBridge();
    bridge.ferric.handle('demo.boom', () => {
        throw new Error('handler exploded');
    });

    bridge.accept({k: 'q', i: 5, n: 'demo.boom'});
    await flush();

    assert.deepStrictEqual(lastSent(bridge), {k: 'r', i: 5, e: 'handler exploded'});
});

test('a rejecting handler replies with an error', async () => {
    const bridge = loadBridge();
    bridge.ferric.handle('demo.reject', () => Promise.reject(new Error('async exploded')));

    bridge.accept({k: 'q', i: 6, n: 'demo.reject'});
    await flush();

    assert.deepStrictEqual(lastSent(bridge), {k: 'r', i: 6, e: 'async exploded'});
});

test('registering two handlers for one channel is rejected', () => {
    const bridge = loadBridge();
    bridge.ferric.handle('demo.only', () => 1);

    assert.throws(() => bridge.ferric.handle('demo.only', () => 2), /demo\.only/);
});

test('malformed envelopes are reported and do not break later ones', () => {
    const bridge = loadBridge();
    const seen = [];
    bridge.ferric.on('demo.tick', () => seen.push('tick'));

    bridge.accept(null);
    bridge.accept('not an object');
    bridge.accept({k: 'zzz', n: 'demo.tick'});
    assert.deepStrictEqual(seen, []);
    assert.ok(bridge.errors.length >= 3, bridge.errors.join('\n'));

    bridge.accept({k: 'e', n: 'demo.tick'});
    assert.deepStrictEqual(seen, ['tick']);
});

test('a reply for an unknown call id is reported, not thrown', () => {
    const bridge = loadBridge();

    bridge.accept({k: 'r', i: 999, p: 'stray'});

    assert.ok(bridge.errors.some((line) => line.includes('999')), bridge.errors.join('\n'));
});

test('resource builds URLs and encodes query parameters', () => {
    const bridge = loadBridge();

    assert.strictEqual(
        bridge.ferric.resource('minecraft/textures/item/apple.png'),
        'ferric://minecraft/textures/item/apple.png');
    assert.strictEqual(
        bridge.ferric.resource('item/minecraft:dragon_head', {size: 48}),
        'ferric://item/minecraft:dragon_head?size=48');
    assert.strictEqual(
        bridge.ferric.resource('entity/minecraft:zombie', {size: 128, mode: 'a b'}),
        'ferric://entity/minecraft:zombie?size=128&mode=a%20b');
    assert.strictEqual(
        bridge.ferric.resource('a/b', {}), 'ferric://a/b', 'an empty query adds no question mark');
});
