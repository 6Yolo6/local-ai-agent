package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GyingBridgeTest {
 @TempDir Path temp;
 @Test void claimsOnlyCatalogScopeAndRetriesDeliveryWithoutReplayingInference() throws Exception {
  ObjectMapper mapper=new ObjectMapper();AssistantService assistant=mock(AssistantService.class);
  when(assistant.run(any())).thenReturn(mapper.createObjectNode().put("scope","gying").put("answer","fixture"));
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));
  AtomicInteger claims=new AtomicInteger(), deliveries=new AtomicInteger();AtomicReference<ObjectNode> received=new AtomicReference<>();
  CountDownLatch completed=new CountDownLatch(1); String id=UUID.randomUUID().toString(),lease=UUID.randomUUID().toString();
  server.createContext("/",e->{
   assertThat(e.getRequestHeaders().getFirst("X-AI-Bridge-Token")).isEqualTo("fixture-ai-bridge-token");
   assertThat(e.getRequestMethod()).isEqualTo("POST");
   int status=200;ObjectNode output=mapper.createObjectNode();
   if(e.getRequestURI().getPath().endsWith("/claim") && claims.getAndIncrement()==0) {
    output.put("jobId",id).put("lease",lease);
    output.putObject("request").put("message","星际穿越").put("scope","local").putArray("history");
   } else if(e.getRequestURI().getPath().endsWith("/"+id)) {
    received.set((ObjectNode)mapper.readTree(e.getRequestBody()));
    if(deliveries.incrementAndGet()==1) status=503; else completed.countDown();
   }
   byte[] bytes=output.toString().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
  });server.start();
  Path token=temp.resolve("token.txt");Files.writeString(token,"fixture-ai-bridge-token");
  GyingBridge bridge=new GyingBridge(assistant,mapper,"http://127.0.0.1:"+server.getAddress().getPort(),token.toString());
  try {
   bridge.start();assertThat(completed.await(10,TimeUnit.SECONDS)).isTrue();
   verify(assistant,times(1)).run(argThat(request->"gying".equals(request.path("scope").asText())));
   assertThat(received.get().path("lease").asText()).isEqualTo(lease);
   assertThat(deliveries).hasValue(2);
  } finally {bridge.close();server.stop(0);}
 }
}
