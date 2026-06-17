'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { appendRelaySection } = require('../src/submission');

test('appendRelaySection embeds screenshots as markdown images', () => {
  const body = appendRelaySection(
    '## 概要\n截图测试',
    'request-1',
    null,
    [
      {
        name: 'combat.png',
        url: 'https://github.com/owner/repo/releases/download/feedback/combat.png'
      }
    ]
  );

  assert.match(body, /## 截图/);
  assert.match(
    body,
    /!\[combat\.png\]\(https:\/\/github\.com\/owner\/repo\/releases\/download\/feedback\/combat\.png\)/
  );
  assert.doesNotMatch(
    body,
    /- \[combat\.png\]\(https:\/\/github\.com\/owner\/repo\/releases\/download\/feedback\/combat\.png\)/
  );
});
