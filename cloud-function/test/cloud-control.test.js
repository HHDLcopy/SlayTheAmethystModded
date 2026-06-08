'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { createApp } = require('../src/createApp');

function buildTestConfig(overrides = {}) {
  return {
    bundleMaxBytes: 1024 * 1024,
    sharedSecret: '',
    githubWebhookSecret: '',
    githubAuth: {
      mode: 'token',
      token: 'test-token'
    },
    mail: {
      enabled: false,
      transport: null
    },
    githubOwner: 'owner',
    githubRepo: 'repo',
    staticLabels: [],
    diagnosticsOwner: 'owner',
    diagnosticsRepo: 'diagnostics',
    diagnosticsBranch: 'main',
    diagnosticsReleasePrefix: 'feedback',
    notificationStateOwner: 'owner',
    notificationStateRepo: 'diagnostics',
    notificationStateBranch: 'main',
    notificationStateReleasePrefix: 'feedback-mail-state',
    cloudControlConfigUrl: '',
    ...overrides
  };
}

test('cloud-control compatibility endpoint redirects to presence service config', async () => {
  const { server, baseUrl } = await listen(createApp(buildTestConfig({
    cloudControlConfigUrl: 'https://presence.example.com/cloud-control.json'
  })));
  try {
    const response = await fetch(`${baseUrl}/cloud-control.json`, {
      redirect: 'manual'
    });

    assert.equal(response.status, 302);
    assert.equal(response.headers.get('location'), 'https://presence.example.com/cloud-control.json');
    assert.equal(response.headers.get('cache-control'), 'no-cache');
  } finally {
    server.close();
  }
});

test('cloud-control compatibility endpoint returns gone without presence service config', async () => {
  const { server, baseUrl } = await listen(createApp(buildTestConfig()));
  try {
    const response = await fetch(`${baseUrl}/cloud-control.json`);
    const body = await response.json();

    assert.equal(response.status, 410);
    assert.equal(body.ok, false);
    assert.equal(body.error, 'presence_moved');
  } finally {
    server.close();
  }
});

function listen(app) {
  return new Promise((resolve) => {
    const server = app.listen(0, () => {
      const address = server.address();
      resolve({
        server,
        baseUrl: `http://127.0.0.1:${address.port}`
      });
    });
  });
}
