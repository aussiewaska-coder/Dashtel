const fs = require('node:fs');
const crypto = require('node:crypto');

// Public, read-only readiness check. Never return deployment or credential details.
exports.handler = function (context, event, callback) {
  let ready = false;
  try {
    const asset = Runtime.getAssets()['/fcm-service-account.json'];
    const account = JSON.parse(fs.readFileSync(asset.path, 'utf8'));
    crypto.createPrivateKey(account.private_key);
    ready = Boolean(account.project_id && account.client_email && context.TWILIO_NUMBER);
  } catch { /* An incomplete relay must not replace an existing SMS route. */ }
  const response = new Twilio.Response();
  response.appendHeader('Content-Type', 'application/json');
  response.appendHeader('Cache-Control', 'no-store');
  response.setBody({ ready });
  callback(null, response);
};
