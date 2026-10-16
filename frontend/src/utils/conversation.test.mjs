import test from 'node:test'
import assert from 'node:assert/strict'
import { buildHistory } from './conversation.mjs'

const pair = (user, assistant, context = true) => [
  { role: 'user', content: user, context }, { role: 'assistant', content: assistant, context }
]
test('history includes only complete successful turns, newest four first in chronological order', () => {
  const messages = Array.from({ length: 6 }, (_, i) => pair('user ' + i, 'answer ' + i)).flat()
  messages.push(...pair('failed question', 'network error', false))
  const result = buildHistory(messages, 'next')
  assert.equal(result.length, 8)
  assert.equal(result[0].content, 'user 2')
  assert.equal(result.at(-1).content, 'answer 5')
  assert.ok(result.every(item => Object.keys(item).length === 2))
})
test('a long latest response is trimmed while retaining its paired user prompt', () => {
  const result = buildHistory(pair('u'.repeat(3000), 'a'.repeat(8000)), 'q'.repeat(3000))
  assert.equal(result.length, 2)
  assert.equal(result[0].content.length, 3000)
  assert.equal(result[1].content.length, 4000)
})
test('older turns are discarded before the request exceeds the backend budget', () => {
  const messages = [...pair('old question', 'o'.repeat(5000)), ...pair('new question', 'a'.repeat(5000))]
  const query = 'q'.repeat(3000)
  const result = buildHistory(messages, query)
  assert.equal(result.length, 2)
  assert.equal(result[0].content, 'new question')
  assert.ok(query.length + result.reduce((total, item) => total + item.content.length, 0) <= 10000)
})
test('empty history stays empty', () => assert.deepEqual(buildHistory([], 'hello'), []))
