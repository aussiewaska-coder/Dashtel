const crypto = require('node:crypto');
const https = require('node:https');
const fs = require('node:fs');

let cachedToken;

function pushPayload(context, event, now = Date.now()) {
  if (event.AccountSid !== context.ACCOUNT_SID || event.To !== context.TWILIO_NUMBER) {
    throw new Error('SMS account or line mismatch');
  }
  if (!/^[SM]M[0-9a-fA-F]{32}$/.test(event.MessageSid || '') ||
      !/^[a-zA-Z0-9_:-]{32,512}$/.test(event.device || '') ||
      typeof event.From !== 'string' || !event.From || event.From.length > 128) {
    throw new Error('Invalid SMS event');
  }
  // Leave room for FCM metadata; the app retrieves the complete body from Twilio.
  let body = '';
  for (const character of String(event.Body || '')) {
    if (Buffer.byteLength(body + character, 'utf8') > 1800) break;
    body += character;
  }
  return {
    message: {
      token: event.device,
      android: { priority: 'high', ttl: '86400s' },
      data: {
        type: 'dashtel.sms', accountSid: event.AccountSid, to: event.To,
        sender: event.From, messageSid: event.MessageSid, body,
        mediaCount: String(Math.max(0, Number.parseInt(event.NumMedia, 10) || 0)),
        timestamp: String(now),
      },
    },
  };
}

function requestJson(url, body, headers) {
  return new Promise((resolve, reject) => {
    const request = https.request(url, {
      method: 'POST', headers: { ...headers, 'Content-Length': Buffer.byteLength(body) }, timeout: 4000,
    }, response => {
      let data = '';
      response.setEncoding('utf8');
      response.on('data', chunk => { data += chunk; });
      response.on('end', () => {
        // Upstream response bodies can contain credentials or phone metadata.
        if (response.statusCode < 200 || response.statusCode >= 300) {
          const error = new Error(`Push provider HTTP ${response.statusCode}`);
          error.status = response.statusCode;
          reject(error);
        } else {
          try { resolve(JSON.parse(data)); } catch { reject(new Error('Invalid push provider response')); }
        }
      });
      response.on('error', () => reject(new Error('Push provider response failed')));
    });
    request.on('timeout', () => request.destroy(new Error('Push provider timeout')));
    request.on('error', () => reject(new Error('Push provider request failed')));
    request.end(body);
  });
}

async function deliver(serviceAccount, payload, request = requestJson, now = Date.now()) {
  if (!serviceAccount.project_id || !serviceAccount.client_email || !serviceAccount.private_key) {
    throw new Error('Firebase service account is incomplete');
  }
  if (!cachedToken || cachedToken.email !== serviceAccount.client_email || cachedToken.expires <= now) {
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const unsigned = `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode({
      iss: serviceAccount.client_email,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: 'https://oauth2.googleapis.com/token', iat: Math.floor(now / 1000), exp: Math.floor(now / 1000) + 3600,
    })}`;
    const signature = crypto.sign('RSA-SHA256', Buffer.from(unsigned), serviceAccount.private_key).toString('base64url');
    const result = await request('https://oauth2.googleapis.com/token', new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${signature}`,
    }).toString(), { 'Content-Type': 'application/x-www-form-urlencoded' });
    if (!result.access_token) throw new Error('Firebase authorization failed');
    cachedToken = { email: serviceAccount.client_email, value: result.access_token,
      expires: now + Math.min(Number(result.expires_in) || 3600, 3600) * 1000 - 60000 };
  }
  try {
    await request(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(serviceAccount.project_id)}/messages:send`,
      JSON.stringify(payload), { 'Content-Type': 'application/json', Authorization: `Bearer ${cachedToken.value}` });
  } catch (error) {
    if (error.status === 401) cachedToken = undefined;
    throw error;
  }
}

// The .protected.js suffix requires Twilio's signature before this handler runs.
exports.handler = async function (context, event, callback) {
  let stage = 'validate-event';
  try {
    const payload = pushPayload(context, event);
    stage = 'load-firebase-credential';
    const asset = Runtime.getAssets()['/fcm-service-account.json'];
    if (!asset) throw new Error('SMS Firebase private asset is missing');
    const serviceAccount = JSON.parse(fs.readFileSync(asset.path, 'utf8'));
    stage = 'send-firebase-push';
    await deliver(serviceAccount, payload);
    stage = 'respond-to-twilio';
    callback(null, new Twilio.twiml.MessagingResponse());
  } catch (error) {
    const status = Number.isInteger(error.status) ? error.status : 'none';
    console.error(`SMS relay failed at ${stage}; provider HTTP status ${status}`);
    // Fail visibly at Twilio, without logging message text, numbers, topics, or credentials.
    callback(new Error('SMS notification relay failed; check private Firebase asset and SMS configuration'));
  }
};

exports.pushPayload = pushPayload;
exports.deliver = deliver;
