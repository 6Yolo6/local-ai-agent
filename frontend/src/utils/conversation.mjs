// Backend limits: 3,000 characters per query, 6,000 per history message,
// 10,000 in total. Keep up to four successful, complete conversation turns.
export function buildHistory(messages, message, scope = 'local') {
  const successful = messages.filter(item => item.context && (item.scope || 'local') === scope).slice(-8)
  const turns = []
  let remaining = Math.max(0, 10000 - message.length)
  for (let i = successful.length - 2; i >= 0; i -= 2) {
    const user = successful[i]
    const assistant = successful[i + 1]
    if (user.role !== 'user' || assistant.role !== 'assistant') continue
    const userContent = user.content.slice(0, 6000)
    let assistantContent = assistant.content.slice(0, 6000)
    if (userContent.length >= remaining) break
    if (userContent.length + assistantContent.length > remaining) {
      if (turns.length) break
      assistantContent = assistantContent.slice(0, remaining - userContent.length)
    }
    turns.unshift({ role: 'user', content: userContent }, { role: 'assistant', content: assistantContent })
    remaining -= userContent.length + assistantContent.length
  }
  return turns
}
