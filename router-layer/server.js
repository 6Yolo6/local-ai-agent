const express = require("express");
const bodyParser = require("body-parser");
const axios = require("axios");
const fs = require("fs");
const path = require("path");

const app = express();
app.use(bodyParser.json({ limit: "1mb" }));

const routerPath = path.join(__dirname, "router.json");
const routerConfig = JSON.parse(fs.readFileSync(routerPath, "utf-8"));

const classifier = routerConfig.semanticRouter.classifier;
const routingTable = routerConfig.semanticRouter.routingTable;
const allowedTypes = new Set(["chat", "reasoning", "code", "rag", "tool"]);

async function classifyTask(userInput) {
  const messages = [
    { role: "system", content: classifier.prompt },
    { role: "user", content: userInput }
  ];

  try {
    const res = await axios.post(`${classifier.baseUrl}/chat/completions`, {
      model: classifier.model,
      messages,
      stream: false
    });

    const content = res.data?.choices?.[0]?.message?.content || "{}";
    const jsonMatch = content.match(/\{[\s\S]*\}/);
    const parsed = JSON.parse(jsonMatch ? jsonMatch[0] : content);

    if (!allowedTypes.has(parsed.type)) {
      return { type: "chat", complexity: "low" };
    }
    return parsed;
  } catch (err) {
    console.error("Classifier failed:", err.message);
    return { type: "chat", complexity: "low" };
  }
}

async function routeTask(taskInfo, userInput) {
  const route = routingTable[taskInfo.type] || routingTable.chat;
  if (!route) throw new Error(`No route found for ${taskInfo.type}`);

  if (route.provider === "ollama" || route.provider === "llama-local") {
    const res = await axios.post(`${route.baseUrl}/chat/completions`, {
      model: route.model,
      messages: [{ role: "user", content: userInput }],
      stream: false
    });
    return res.data;
  }

  if (route.provider === "local-ai-agent") {
    const res = await axios.post(route.endpoint, {
      prompt: userInput,
      system: "你是本地 AI Agent，优先给出可执行、简洁、准确的答复。",
      engine: route.engine || "llama"
    });
    return res.data;
  }

  if (route.provider === "anythingllm") {
    const res = await axios.post(route.endpoint, { input: userInput });
    return res.data;
  }

  throw new Error(`Unknown provider: ${route.provider}`);
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "router-layer" });
});

app.post("/api/agent", async (req, res) => {
  const userInput = String(req.body?.input || "").trim();
  if (!userInput) return res.status(400).json({ error: "input is required" });

  try {
    const taskInfo = await classifyTask(userInput);
    const result = await routeTask(taskInfo, userInput);
    res.json({ taskInfo, result });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: err.message });
  }
});

const PORT = Number(process.env.ROUTER_PORT || 5002);
app.listen(PORT, "127.0.0.1", () => {
  console.log(`Router Agent running on http://127.0.0.1:${PORT}`);
});
