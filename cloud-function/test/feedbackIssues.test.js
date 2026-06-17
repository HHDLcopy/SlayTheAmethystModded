'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { buildIssueMessageCommentBody } = require('../src/feedbackIssues');

test('buildIssueMessageCommentBody embeds screenshots as markdown images', () => {
  const result = buildIssueMessageCommentBody(
    {
      issueNumber: 7,
      messageText: '追加截图',
      playerName: 'player',
      appVersion: '1.0.0',
      deviceLabel: 'Android Device'
    },
    [
      {
        name: 'map.png',
        url: 'https://github.com/owner/repo/releases/download/feedback/map.png'
      }
    ],
    null
  );

  assert.match(result.body, /### 截图/);
  assert.match(
    result.body,
    /!\[map\.png\]\(https:\/\/github\.com\/owner\/repo\/releases\/download\/feedback\/map\.png\)/
  );
  assert.doesNotMatch(
    result.body,
    /- \[map\.png\]\(https:\/\/github\.com\/owner\/repo\/releases\/download\/feedback\/map\.png\)/
  );
});
