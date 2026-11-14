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

// Página inicial: busca e filtro da lista (só aparecem com muitas instâncias). Ficam fora do trecho
// ao vivo; depois de cada troca (SSE ou polling) o filtro é aplicado de novo às linhas novas.
(function () {
    'use strict';

    var tools = null;

    function apply() {
        if (!tools) {
            return;
        }
        var term = tools.querySelector('#instance-search').value.trim().toLowerCase();
        var state = tools.querySelector('#instance-filter').value;
        var rows = document.querySelectorAll('#instances-table tr[data-instance]');
        var shown = 0;
        for (var i = 0; i < rows.length; i++) {
            var row = rows[i];
            var byText = term === '' || (row.getAttribute('data-search') || '').indexOf(term) !== -1;
            var byState = state === ''
                || (state === 'attention' ? row.hasAttribute('data-attention') : row.getAttribute('data-badge') === state);
            row.hidden = !(byText && byState);
            if (!row.hidden) {
                shown++;
            }
        }
        var filtering = term !== '' || state !== '';
        var empty = document.getElementById('filter-empty');
        if (empty) {
            empty.hidden = !(filtering && shown === 0 && rows.length > 0);
        }
        var status = document.getElementById('filter-status');
        var text = filtering
            ? (tools.getAttribute('data-count-text') || '').replace('{0}', shown).replace('{1}', rows.length)
            : '';
        if (status && status.textContent !== text) {
            status.textContent = text;
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        tools = document.querySelector('[data-list-filter]');
        if (!tools) {
            return;
        }
        tools.hidden = false;
        tools.addEventListener('input', apply);
        tools.addEventListener('change', apply);
        // Enter na busca não recarrega a página: o filtro já está aplicado
        tools.addEventListener('submit', function (evt) { evt.preventDefault(); });
        apply();
    });
    document.addEventListener('htmx:afterSettle', apply);
})();
