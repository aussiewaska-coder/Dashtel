const E164 = /^\+[1-9]\d{6,14}$/;

function planCall(context, event) {
  const number = String(context.TWILIO_NUMBER || '').trim();
  const destination = String(event.To || '').trim();

  if (!E164.test(number)) return { type: 'reject', reason: 'line-not-configured' };
  if (destination === number) {
    return {
      type: 'client',
      identity: String(context.DEFAULT_CLIENT_IDENTITY || 'default').trim() || 'default',
    };
  }
  if (!E164.test(destination)) return { type: 'reject', reason: 'invalid-destination' };
  return {
    type: 'number',
    destination,
    callerId: number,
    record: String(context.RECORD_CALLS || '').toLowerCase() === 'true',
  };
}

exports.handler = function (context, event, callback) {
  const response = new Twilio.twiml.VoiceResponse();
  const plan = planCall(context, event);

  if (plan.type === 'reject') {
    response.say(
      plan.reason === 'line-not-configured'
        ? 'This phone line is not configured. Please try again later.'
        : 'The number you dialed is not valid.'
    );
    response.hangup();
    return callback(null, response);
  }

  if (plan.type === 'client') {
    response.dial().client(plan.identity);
    return callback(null, response);
  }

  const dialOptions = { callerId: plan.callerId };
  if (plan.record) {
    dialOptions.record = 'record-from-answer-dual';
  }
  response.dial(dialOptions).number(plan.destination);
  return callback(null, response);
};

// Exported for dependency-free unit tests; Twilio invokes only `handler`.
exports.planCall = planCall;
