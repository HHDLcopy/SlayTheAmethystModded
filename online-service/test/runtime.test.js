'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { buildMappedListeners } = require('../src/server/runtime');

test('mapped listeners preserve the public entry node URL', () => {
  assert.deepEqual(
    buildMappedListeners({
      easyTierEntryNodeUrl: 'tcp://frp-dog.com:12332',
      easyTierEntryNodePort: 11010,
      publicBaseUrl: 'https://heartbeat.nas.apricityx.top:23163'
    }),
    ['tcp://frp-dog.com:12332']
  );
});

test('mapped listeners fall back to the local entry node port without a public URL', () => {
  assert.deepEqual(
    buildMappedListeners({
      easyTierEntryNodeUrl: '',
      easyTierEntryNodePort: 11010,
      publicBaseUrl: 'https://heartbeat.nas.apricityx.top:23163'
    }),
    [
      'tcp://heartbeat.nas.apricityx.top:11010',
      'udp://heartbeat.nas.apricityx.top:11010'
    ]
  );
});
