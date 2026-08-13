// FerricOxide bridge runtime.
//
// Injected as a WebView initialization script before any page script runs, so pages can use
// `ferric` unconditionally — no feature detection, no load-order concerns.
//
// Wire protocol (shared with the Java side, see docs/webui-bridge.md):
//   {"k":"e","n":name,"p":payload}          event, one-way
//   {"k":"q","i":id,"n":name,"p":payload}   query, expects a reply
//   {"k":"r","i":id,"p":payload}            reply, success
//   {"k":"r","i":id,"e":message}            reply, failure
//
// The resource-base placeholder below is substituted by Java (see WebBridgeImpl.initScript) with
// the platform-correct resource URL prefix. It must occur exactly once in this file.
(function () {
    'use strict';

    var RESOURCE_BASE = '__RESOURCE_BASE__';

    var listeners = {};   // event name -> handler array
    var handlers = {};    // query name -> single handler
    var pending = {};     // outbound call id -> {resolve, reject}
    var nextId = 1;

    function send(envelope) {
        window.ipc.postMessage(JSON.stringify(envelope));
    }

    function reply(id, payload, error) {
        var envelope = {k: 'r', i: id};
        if (error === undefined) {
            envelope.p = payload === undefined ? null : payload;
        } else {
            envelope.e = String(error);
        }
        send(envelope);
    }

    // Dispatches an inbound query to its handler, tolerating both plain and Promise returns.
    function dispatchQuery(envelope) {
        var handler = handlers[envelope.n];
        if (!handler) {
            reply(envelope.i, null, 'no handler: ' + envelope.n);
            return;
        }
        var result;
        try {
            result = handler(envelope.p);
        } catch (error) {
            console.error('[ferric] handler for "' + envelope.n + '" threw', error);
            reply(envelope.i, null, error && error.message ? error.message : error);
            return;
        }
        Promise.resolve(result).then(
            function (value) {
                reply(envelope.i, value);
            },
            function (error) {
                console.error('[ferric] handler for "' + envelope.n + '" rejected', error);
                reply(envelope.i, null, error && error.message ? error.message : error);
            }
        );
    }

    function dispatchEvent(envelope) {
        var registered = listeners[envelope.n];
        if (!registered || registered.length === 0) {
            console.warn('[ferric] no listener for event "' + envelope.n + '"');
            return;
        }
        for (var i = 0; i < registered.length; i++) {
            try {
                registered[i](envelope.p);
            } catch (error) {
                console.error('[ferric] listener for "' + envelope.n + '" threw', error);
            }
        }
    }

    function dispatchReply(envelope) {
        var slot = pending[envelope.i];
        if (!slot) {
            console.warn('[ferric] reply for unknown call id ' + envelope.i);
            return;
        }
        delete pending[envelope.i];
        if (envelope.e === undefined) {
            slot.resolve(envelope.p);
        } else {
            slot.reject(new Error(envelope.e));
        }
    }

    var ferric = {
        /** Sends a one-way event to Java. */
        emit: function (name, payload) {
            send({k: 'e', n: name, p: payload === undefined ? null : payload});
        },

        /** Sends a query to Java and resolves with its reply payload. */
        call: function (name, payload) {
            var id = nextId++;
            return new Promise(function (resolve, reject) {
                pending[id] = {resolve: resolve, reject: reject};
                send({k: 'q', i: id, n: name, p: payload === undefined ? null : payload});
            });
        },

        /** Subscribes to a Java event. Returns an unsubscribe function. */
        on: function (name, handler) {
            var registered = listeners[name] || (listeners[name] = []);
            registered.push(handler);
            return function () {
                var index = registered.indexOf(handler);
                if (index >= 0) {
                    registered.splice(index, 1);
                }
            };
        },

        /** Answers Java queries on the given channel. One handler per channel. */
        handle: function (name, handler) {
            if (handlers[name]) {
                throw new Error('handler already registered for "' + name + '"');
            }
            handlers[name] = handler;
        },

        /**
         * Builds a game-resource URL, hiding the platform protocol difference.
         * `ferric.resource('item/minecraft:apple', {size: 48})`
         */
        resource: function (path, query) {
            var url = RESOURCE_BASE + path;
            if (!query) {
                return url;
            }
            var parts = [];
            for (var key in query) {
                if (Object.prototype.hasOwnProperty.call(query, key)) {
                    parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(query[key]));
                }
            }
            return parts.length === 0 ? url : url + '?' + parts.join('&');
        }
    };

    // Single entry point used by the Java side: WebUi evaluates `window.__ferric.accept(<json>)`
    // instead of assembling arbitrary JS source.
    window.__ferric = {
        accept: function (envelope) {
            if (!envelope || typeof envelope !== 'object') {
                console.error('[ferric] malformed envelope', envelope);
                return;
            }
            if (envelope.k === 'e') {
                dispatchEvent(envelope);
            } else if (envelope.k === 'q') {
                dispatchQuery(envelope);
            } else if (envelope.k === 'r') {
                dispatchReply(envelope);
            } else {
                console.error('[ferric] unknown envelope kind "' + envelope.k + '"');
            }
        }
    };

    window.ferric = ferric;
})();
