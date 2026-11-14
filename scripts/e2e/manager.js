// E2E do Manager (Playwright). Uso: subir a aplicação (stub) em :8080 (ou MSGFORGE_E2E_URL) e rodar
//   node scripts/e2e/manager.js [pasta-de-screenshots]
// Requer Node + o pacote playwright (já vem nas sessões em nuvem). Chave: MSGFORGE_E2E_KEY ou troque-esta-chave (perfil dev).
const { chromium } = require('playwright');
(async () => {
  const shots = process.argv[2] || require('os').tmpdir();
  const key = process.env.MSGFORGE_E2E_KEY || 'troque-esta-chave';
  const base = process.env.MSGFORGE_E2E_URL || 'http://localhost:8080';
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 2 });
  const problems = [];
  page.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') problems.push(m.type() + ': ' + m.text()); });
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  const step = (s) => console.log('✔ ' + s);

  await page.goto(base + '/manager');
  if (!page.url().endsWith('/manager/login')) throw new Error('sem redirect para login: ' + page.url());
  await page.fill('#apikey', 'errada'); await page.click('button[type=submit]');
  await page.waitForSelector('.flash.error'); step('chave errada recusada');
  await page.screenshot({ path: shots + '/1-login-erro.png' });

  await page.fill('#apikey', key); await page.click('button[type=submit]');
  await page.waitForURL(base + '/manager'); step('login ok');

  // Nova instância guiada: nome → forma de conexão → histórico → ler o QR
  const name = 'vendas-' + Date.now().toString().slice(-5);
  await page.click('a[href$="/manager/instances/new"]'); await page.waitForURL('**/manager/instances/new');
  await page.fill('#instanceName', 'nome inválido'); await page.click('form button.primary');
  await page.waitForSelector('#instanceName-error'); step('passo 1: nome inválido recusado junto do campo');
  await page.fill('#instanceName', name); await page.click('form button.primary');
  await page.waitForURL('**/manager/instances/new/connection'); step('passo 2: forma de conexão');
  if (!(await page.isChecked('#method-qr'))) throw new Error('QR code não veio marcado');
  await page.click('form button.primary'); await page.waitForURL('**/manager/instances/new/history');
  if (!(await page.isChecked('input[name=historySync][value=NONE]'))) throw new Error('histórico sem o padrão seguro');
  await page.goBack(); await page.waitForURL('**/manager/instances/new/connection');
  await page.goForward(); await page.waitForURL('**/manager/instances/new/history'); step('passo 3: voltar e avançar pelo navegador');
  await page.screenshot({ path: shots + '/1b-nova-instancia-historico.png', fullPage: true });
  await page.click('form button.primary'); await page.waitForURL('**/manager/instances/' + name + '/connect');
  const token = await page.textContent('.flash.warning code'); step('passo 4: instância criada, token mostrado uma vez: ' + token.slice(0, 8) + '…');
  await page.waitForSelector('#live .badge.connecting', { timeout: 5000 }); step('conectar → estado "Conectando"');
  await page.waitForSelector('#live code', { timeout: 5000 }); step('QR do stub exibido (sem imagem), com o passo a passo');
  await page.screenshot({ path: shots + '/1c-nova-instancia-qr.png', fullPage: true });

  await page.goto(base + '/manager/instances/' + name);
  await page.waitForSelector('.resume a[href$="/connect"]'); step('painel oferece retomar a conexão');

  await page.fill('input[name=url]', 'https://example.com/hook');
  await page.check('input[name=events][value=MESSAGES_UPSERT]');
  await page.click('form[action$="/webhook"] button[type=submit]');
  await page.waitForSelector('.flash.success'); step('webhook salvo');
  if (!(await page.isChecked('input[name=events][value=MESSAGES_UPSERT]'))) throw new Error('evento não persistiu');
  if ((await page.inputValue('input[name=url]')) !== 'https://example.com/hook') throw new Error('url não persistiu');
  step('webhook recarregado com url e evento');

  await page.fill('input[name=url]', 'ftp://x.test');
  await page.evaluate(() => document.querySelector('input[name=url]').setAttribute('type', 'text'));
  await page.click('form[action$="/webhook"] button[type=submit]');
  await page.waitForSelector('.flash.error'); step('erro do serviço vira mensagem na tela: ' + (await page.textContent('.flash.error')).trim());

  await page.waitForTimeout(3500);   // pelo menos um ciclo do htmx no trecho "live"
  await page.screenshot({ path: shots + '/2-instancia.png', fullPage: true });

  // Mensagens: envio pela API (o stub nunca conecta, e o botão da tela exige conexão), depois a tela
  const sent = await page.request.post(base + '/message/sendText/' + name, {
    headers: { apikey: key }, data: { number: '5561911111111', text: 'olá pelo e2e' } });
  if (sent.status() !== 201) throw new Error('envio pela API: ' + sent.status());
  await page.click('a.button[href$="/messages"]'); await page.waitForURL('**/manager/instances/' + name + '/messages');
  await page.click('.chat-list .chat'); await page.waitForSelector('.bubble.mine >> text=olá pelo e2e');
  step('mensagens: conversa aberta mostra o envio');
  await page.screenshot({ path: shots + '/2b-mensagens.png', fullPage: true });

  await page.goto(base + '/manager');
  await page.waitForSelector('text=' + name);
  await page.screenshot({ path: shots + '/3-lista.png' });
  step('lista mostra a instância');

  const mobile = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  await mobile.context().addCookies(await page.context().cookies());
  await mobile.goto(base + '/manager/instances/' + name);
  const overflow = await mobile.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  await mobile.screenshot({ path: shots + '/4-celular.png', fullPage: true });
  step('celular 390px, rolagem horizontal: ' + overflow);

  await page.goto(base + '/manager/instances/' + name);
  await page.check('input[name=confirm]'); await page.click('button.danger');
  await page.waitForURL(base + '/manager'); await page.waitForSelector('.flash.success'); step('instância excluída');

  await page.click('.topbar-logout button'); await page.waitForURL('**/manager/login?logout'); step('logout do manager');
  const r = await page.goto(base + '/manager'); if (!page.url().endsWith('/manager/login')) throw new Error('sessão continuou');
  step('após sair, /manager volta para o login');

  console.log(problems.length ? 'PROBLEMAS NO CONSOLE:\n' + problems.join('\n') : '✔ console sem erros nem avisos (inclusive CSP)');
  await browser.close();
})().catch(e => { console.error('FALHOU: ' + e.message); process.exit(1); });
