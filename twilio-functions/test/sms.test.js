const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { pushPayload, deliver } = require('../functions/sms.protected');

const context = { ACCOUNT_SID: 'AC' + '0'.repeat(32), TWILIO_NUMBER: '+61400000000' };
const event = { AccountSid: context.ACCOUNT_SID, To: context.TWILIO_NUMBER, From: '+61400000001',
  MessageSid: 'SM' + '0'.repeat(32), device: 'synthetic-device-token-' + 'a'.repeat(100), Body: 'Hello', NumMedia: '0' };

test('incoming SMS produces data-only high priority push and no automatic SMS reply', () => {
  const result = pushPayload(context, event, 123);
  assert.equal(result.message.data.body, 'Hello');
  assert.equal(result.message.data.messageSid, event.MessageSid);
  assert.equal(result.message.data.sender, event.From);
  for (const key of Object.keys(result.message.data)) {
    assert.ok(!['from', 'message_type'].includes(key) && !key.startsWith('google.') && !key.startsWith('gcm.'), `FCM reserved key: ${key}`);
  }
  assert.equal(result.message.data.timestamp, '123');
  assert.equal(result.message.token, event.device);
  assert.equal(result.message.android.priority, 'high');
  assert.equal(result.message.notification, undefined);
});

test('rejects mismatched accounts, numbers, and invalid device tokens or message IDs', () => {
  for (const patch of [{ AccountSid: 'AC' + '1'.repeat(32) }, { To: '+61400000002' },
    { device: 'invalid' }, { MessageSid: '../../bad' }, { From: '' }]) {
    assert.throws(() => pushPayload(context, { ...event, ...patch }));
  }
});

test('Unicode previews fit FCM limits without breaking surrogate pairs', () => {
  const payload = pushPayload(context, { ...event, Body: '😃'.repeat(1600) });
  assert.ok(Buffer.byteLength(JSON.stringify(payload)) < 4096);
  assert.equal(payload.message.data.body, '😃'.repeat(450));
});

test('relay signs OAuth assertion, reuses token and propagates provider failures', async () => {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'test-project', client_email: 'test@example.invalid', private_key: privateKey };
  const calls = [];
  const request = async (url, body, headers) => {
    calls.push({ url, body, headers });
    if (url.includes('oauth2')) {
      const jwt = new URLSearchParams(body).get('assertion');
      const [header, claim, signature] = jwt.split('.');
      assert.ok(crypto.verify('RSA-SHA256', Buffer.from(`${header}.${claim}`), publicKey, Buffer.from(signature, 'base64url')));
      assert.equal(JSON.parse(Buffer.from(claim, 'base64url')).scope, 'https://www.googleapis.com/auth/firebase.messaging');
      return { access_token: 'synthetic-token', expires_in: 3600 };
    }
    assert.equal(headers.Authorization, 'Bearer synthetic-token');
    return { name: 'test-message' };
  };
  await deliver(account, pushPayload(context, event), request, 100000);
  await deliver(account, pushPayload(context, event), request, 101000);
  assert.equal(calls.filter(c => c.url.includes('oauth2')).length, 1);
  assert.equal(calls.filter(c => c.url.includes('fcm')).length, 2);
  await assert.rejects(deliver(account, {}, async () => { throw new Error('unavailable'); }, 102000), /unavailable/);
});
