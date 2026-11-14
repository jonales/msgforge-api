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

// Nova instância guiada e conexão: acessibilidade.
// - Cada passo é uma página nova; o foco vai para o título do passo (que cita "Passo N de 4"),
//   salvo quando um campo pediu o foco (autofocus, inclusive o campo com erro).
// - O trecho ao vivo (#live) é trocado inteiro a cada QR novo; anunciar a troca inteira repetiria a
//   tela. O anúncio sai de um aviso fixo (#live-announce) e só quando o estado ou o código mudam.
(function () {
    'use strict';

    var lastKey = null;

    function announce() {
        var live = document.getElementById('live');
        var region = document.getElementById('live-announce');
        if (!live || !region) {
            return;
        }
        var key = live.getAttribute('data-announce-key');
        if (lastKey === null) {
            lastKey = key;   // a página acabou de abrir: o que está na tela já foi lido
            return;
        }
        if (key !== lastKey) {
            lastKey = key;
            region.textContent = live.getAttribute('data-announce') || '';
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        var heading = document.querySelector('[data-step-heading]');
        if (heading && !document.querySelector('[autofocus]')) {
            heading.focus();
        }
        announce();
    });
    document.addEventListener('htmx:afterSettle', announce);
})();
