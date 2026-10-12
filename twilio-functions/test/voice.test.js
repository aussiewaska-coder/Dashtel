'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { planCall } = require('../functions/voice.protected.js');

const baseContext = {
  TWILIO_NUMBER: '+61200000000',
  DEFAULT_CLIENT_IDENTITY: 'default',
};

test('routes the Twilio number to the configured client', () => {
  assert.deepEqual(planCall(baseContext, { To: '+61200000000' }), {
    type: 'client',
    identity: 'default',
  });
});

test('routes a valid destination with the fixed caller id', () => {
  assert.deepEqual(planCall(baseContext, { To: '+61411111111' }), {
    type: 'number',
    destination: '+61411111111',
    callerId: '+61200000000',
    record: false,
  });
});

test('enables dual-channel recording only when explicitly true', () => {
  const context = { ...baseContext, RECORD_CALLS: 'TRUE' };
  assert.equal(planCall(context, { To: '+61411111111' }).record, true);
});

test('rejects missing line configuration and invalid destinations', () => {
  assert.deepEqual(planCall({}, { To: '+61411111111' }), {
    type: 'reject',
    reason: 'line-not-configured',
  });
  assert.deepEqual(planCall(baseContext, { To: 'not-a-number' }), {
    type: 'reject',
    reason: 'invalid-destination',
  });
});
