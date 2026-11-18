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

// Mensagens: mídia que não carregou (instância desconectada, fora do motor, grande demais) dá lugar
// ao aviso do tipo. O evento "error" de <img>, <audio> e <video> não sobe: escuta na captura.
// Âncora que aponta para um <details> (o proxy): abre ao chegar nele.
(function () {
    'use strict';

    document.addEventListener('error', function (evt) {
        var target = evt.target;
        // Conta sem foto (404) ou foto que não carregou: a imagem sai e ficam as iniciais por baixo
        if (target && target.tagName === 'IMG' && target.parentElement
                && target.parentElement.classList.contains('avatar')) {
            target.remove();
            return;
        }
        if (target && target.hasAttribute && target.hasAttribute('data-media')) {
            var box = target.closest('.media');
            if (box) {
                box.classList.add('media-failed');
            }
        }
    }, true);

    function openTarget() {
        if (!location.hash || location.hash.length < 2) {
            return;
        }
        var target = document.getElementById(decodeURIComponent(location.hash.substring(1)));
        if (target && target.tagName === 'DETAILS' && !target.open) {
            target.open = true;
            target.scrollIntoView();
        }
    }

    document.addEventListener('DOMContentLoaded', openTarget);
    window.addEventListener('hashchange', openTarget);
})();

// Painel da instância, no celular: o menu é uma faixa de abas que rola para o lado dentro dela mesma.
// Ao abrir uma tela, a aba atual entra na faixa (sem rolar a página).
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        var list = document.querySelector('.panel-nav ul');
        var current = list && list.querySelector('[aria-current=page]');
        if (!current || list.scrollWidth <= list.clientWidth) {
            return;
        }
        var item = current.parentElement;
        var left = item.offsetLeft - list.offsetLeft;
        if (left + item.offsetWidth > list.clientWidth - 24) {
            list.scrollLeft = left - 16;
        }
    });
})();

// Mensagens: a conversa aberta rola no próprio painel e abre na mensagem mais recente. Quando chega
// uma mensagem nova (SSE ou polling), a rolagem só desce se a pessoa já estava no fim; se ela subiu
// para ler, fica onde estava. A resposta fica fora do trecho trocado e recebe o jid da conversa.
(function () {
    'use strict';

    var NEAR_END = 80;
    var last = null;   // {jid, page, top, end} da conversa na tela

    function scroller() {
        return document.querySelector('#conversation .conversation-scroll');
    }

    function atEnd(el) {
        return el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_END;
    }

    function remember() {
        var conversation = document.getElementById('conversation');
        var el = scroller();
        last = conversation && el ? {
            jid: conversation.getAttribute('data-jid'),
            page: conversation.getAttribute('data-page'),
            top: el.scrollTop,
            end: atEnd(el)
        } : null;
    }

    function settle() {
        var conversation = document.getElementById('conversation');
        var el = scroller();
        syncReply(conversation);
        if (!el) {
            last = null;
            return;
        }
        var same = last && last.jid === conversation.getAttribute('data-jid')
            && last.page === conversation.getAttribute('data-page');
        el.scrollTop = same && !last.end ? last.top : el.scrollHeight;
        remember();
    }

    function syncReply(conversation) {
        var form = document.getElementById('reply');
        if (!form) {
            return;
        }
        var jid = conversation && conversation.getAttribute('data-jid');
        var field = form.querySelector('input[name=jid]');
        if (field.value !== (jid || '')) {
            field.value = jid || '';
            form.querySelector('textarea').value = '';
            status('');
        }
        form.hidden = !jid;
    }

    function status(text) {
        var box = document.getElementById('reply-status');
        if (box) {
            box.textContent = text;
        }
    }

    document.addEventListener('scroll', function (evt) {
        if (evt.target && evt.target.classList && evt.target.classList.contains('conversation-scroll')) {
            remember();
        }
    }, true);

    // Imagem que carrega depois empurra o fim para baixo: quem estava no fim continua nele
    document.addEventListener('load', function (evt) {
        var el = scroller();
        if (el && el.contains(evt.target) && (!last || last.end)) {
            el.scrollTop = el.scrollHeight;
        }
    }, true);

    document.addEventListener('DOMContentLoaded', settle);
    document.addEventListener('htmx:load', function (evt) {
        if (evt.target && evt.target.id === 'conversation') {
            settle();
        }
    });

    // Enviada: limpa a caixa (a conversa nova já veio na resposta). Recusada (422): mostra o aviso
    document.addEventListener('htmx:afterRequest', function (evt) {
        var form = evt.detail.elt;
        if (!form || form.id !== 'reply') {
            return;
        }
        if (evt.detail.successful) {
            form.querySelector('textarea').value = '';
            status('');
            var el = scroller();
            if (el) {
                el.scrollTop = el.scrollHeight;
            }
        } else {
            var reply = new DOMParser().parseFromString(evt.detail.xhr.responseText || '', 'text/html');
            status(reply.body.textContent.trim());
        }
    });

    // Enter envia; Shift+Enter quebra a linha
    document.addEventListener('keydown', function (evt) {
        if (evt.key !== 'Enter' || evt.shiftKey || evt.isComposing || evt.target.id !== 'reply-text') {
            return;
        }
        evt.preventDefault();
        if (evt.target.value.trim() !== '') {
            evt.target.form.requestSubmit();
        }
    });
})();

// Medidor de força da senha (criar usuário): as regras da PasswordPolicy que o navegador consegue ver
// (tamanho, repetição, sequência, e-mail e nome). Só orienta: quem aceita ou recusa é o servidor, que
// também confere a lista de senhas comuns. Os textos vêm do HTML (data-text-*).
(function () {
    'use strict';
    var SEQUENCIAS = ['abcdefghijklmnopqrstuvwxyz', '0123456789', 'qwertyuiopasdfghjklçzxcvbnm',
        'qwertyuiopasdfghjklzxcvbnm', '1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p', 'qazwsxedcrfvtgbyhnujmikolp'];

    function simples(texto) { return texto.toLowerCase().replace(/\s+/g, ''); }
    function tamanho(texto) { return Array.from(texto).length; }

    function repetitiva(p) {
        return new Set(Array.from(p)).size <= 3 || /^(.{1,7})\1+.{0,3}$/u.test(p);
    }

    function sequencia(p, minimo) {
        return SEQUENCIAS.some(function (base) {
            var volta = base.repeat(3);
            var avesso = Array.from(volta).reverse().join('');
            for (var corte = 0; corte <= 2 && p.length - corte >= minimo - 2; corte++) {
                var miolo = p.slice(0, p.length - corte);
                if (volta.indexOf(miolo) >= 0 || avesso.indexOf(miolo) >= 0) {
                    return true;
                }
            }
            return false;
        });
    }

    function quaseContexto(p, palavras, minimo) {
        var resto = p;
        palavras.concat(['msgforge']).forEach(function (w) { resto = resto.split(w).join(''); });
        return resto.length < minimo - 5 && resto.length < p.length;
    }

    function palavrasDe(ids) {
        var lista = [];
        ids.forEach(function (id) {
            var campo = document.getElementById(id);
            if (campo && campo.value) {
                simples(campo.value).split(/[@._+\-]/).forEach(function (w) {
                    if (w.length >= 4) { lista.push(w); }
                });
            }
        });
        return lista;
    }

    function avaliar(box, input) {
        var minimo = parseInt(box.getAttribute('data-min'), 10) || 15;
        var valor = input.value;
        var p = simples(valor);
        var n = tamanho(valor);
        var estado, nivel;
        if (!valor) {
            estado = 'empty'; nivel = 0;
        } else if (n < minimo) {
            estado = 'short'; nivel = 1;
        } else if (repetitiva(p)) {
            estado = 'repetitive'; nivel = 1;
        } else if (sequencia(p, minimo)) {
            estado = 'sequence'; nivel = 1;
        } else if (quaseContexto(p, palavrasDe((box.getAttribute('data-context-fields') || '').split(/\s+/)), minimo)) {
            estado = 'context'; nivel = 1;
        } else if (n >= minimo + 5) {
            estado = 'strong'; nivel = 3;
        } else {
            estado = 'ok'; nivel = 2;
        }
        var texto = (box.getAttribute('data-text-' + estado) || '').replace('{0}', String(minimo - n));
        box.querySelector('.meter-state').textContent = texto;
        box.setAttribute('data-level', String(nivel));
        box.setAttribute('data-state', estado);
    }

    function ligar() {
        document.querySelectorAll('[data-password-meter]').forEach(function (box) {
            var input = document.getElementById(box.getAttribute('data-password-meter'));
            if (!input || box.dataset.ligado) { return; }
            box.dataset.ligado = '1';
            var atualizar = function () { avaliar(box, input); };
            input.addEventListener('input', atualizar);
            (box.getAttribute('data-context-fields') || '').split(/\s+/).forEach(function (id) {
                var campo = document.getElementById(id);
                if (campo) { campo.addEventListener('input', atualizar); }
            });
            atualizar();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', ligar);
    } else {
        ligar();
    }
})();
