// Manager: tempo real (ADR 0013). Os trechos chegam pelo SSE (extensão sse do htmx); o polling
// do htmx continua nos mesmos trechos (atributo data-poll) como reserva:
// - stream aberto: o polling fica suspenso, com uma busca de segurança a cada minuto;
// - stream com erro, fechado ou sem suporte: o polling volta sozinho no ritmo de sempre.
(function () {
    'use strict';

    var SAFETY_NET_MS = 60000;
    var lastPoll = {};

    function mark(evt, state) {
        if (evt.target && evt.target.setAttribute) {
            evt.target.setAttribute('data-sse-state', state);
        }
    }

    function streamOpen(elt) {
        var root = elt.closest('[sse-connect]');
        return root !== null && root.getAttribute('data-sse-state') === 'open';
    }

    document.addEventListener('htmx:sseOpen', function (evt) { mark(evt, 'open'); });
    document.addEventListener('htmx:sseError', function (evt) { mark(evt, 'error'); });
    document.addEventListener('htmx:sseClose', function (evt) { mark(evt, 'closed'); });

    document.addEventListener('htmx:beforeRequest', function (evt) {
        var elt = evt.detail.elt;
        if (!elt.hasAttribute('data-poll') || !streamOpen(elt)) {
            return;
        }
        var key = elt.getAttribute('hx-get');
        var now = Date.now();
        if (lastPoll[key] === undefined) {
            lastPoll[key] = now;
        }
        if (now - lastPoll[key] < SAFETY_NET_MS) {
            evt.preventDefault();
        } else {
            lastPoll[key] = now;
        }
    });
})();
