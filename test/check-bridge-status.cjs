const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  page.on('console', msg => {
    const text = msg.text();
    if (text.includes('Bridge') || text.includes('NIP55') || text.includes('client') ||
        text.includes('dependencies') || text.includes('BRIDGE') || text.includes('disabled') ||
        text.includes('locked') || text.includes('online')) {
      console.log('[BROWSER]', text);
    }
  });

  await page.goto('http://localhost:3000');
  await page.waitForTimeout(5000);

  // Check bridge initialization status
  const bridgeStatus = await page.evaluate(() => {
    return {
      hasNostr: typeof window.nostr !== 'undefined',
      hasNip55: typeof window.nostr?.nip55 !== 'undefined',
      hasWindow: typeof window !== 'undefined'
    };
  });

  console.log('Bridge Status:', JSON.stringify(bridgeStatus, null, 2));
  await browser.close();
})();