package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Ephemeral bounded audit feed. No prompts or model answer text are kept here. */
@Service
public class TraceStore {
    private final Deque<ObjectNode> entries = new ArrayDeque<>();
    public synchronized void add(ObjectNode entry) {
        ObjectNode audit = entry.deepCopy();
        // File contents are available in this request's trace only, never the global log feed.
        if (audit.path("result").path("data").has("content"))
            ((ObjectNode) audit.path("result").path("data")).put("content", "[文件正文已省略]");
        entries.addFirst(audit);
        while (entries.size() > 200) entries.removeLast();
    }
    public synchronized List<ObjectNode> recent() {
        return entries.stream().map(ObjectNode::deepCopy).toList();
    }
}
