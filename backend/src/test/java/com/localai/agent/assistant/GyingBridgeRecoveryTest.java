package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GyingBridgeRecoveryTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    String id=UUID.randomUUID().toString(),lease=UUID.randomUUID().toString();
    AtomicInteger claims=new AtomicInteger(),deliveries=new AtomicInteger(),renewals=new AtomicInteger();
    AtomicInteger deliveryStatus=new AtomicInteger(503),renewStatus=new AtomicInteger(200);
    AtomicReference<ObjectNode> received=new AtomicReference<>();
    HttpServer server;
    String origin() {return "http://127.0.0.1:"+server.getAddress().getPort();}
    void serve() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));
        server.createContext("/",e->{
            String path=e.getRequestURI().getPath();ObjectNode output=mapper.createObjectNode();int status=200;
            if(path.endsWith("/claim") && claims.incrementAndGet()==1) {
                output.put("jobId",id).put("lease",lease);output.putObject("request").put("message","fixture-private-question").putArray("history");
            } else if(path.endsWith("/renew")) {renewals.incrementAndGet();status=renewStatus.get();}
            else if(path.endsWith("/"+id)) {deliveries.incrementAndGet();received.set((ObjectNode)mapper.readTree(e.getRequestBody()));status=deliveryStatus.get();}
            byte[] bytes=mapper.writeValueAsBytes(output);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();Files.writeString(directory.resolve("token"),"fixture-bridge-token-at-least-32-characters");
        Files.write(directory.resolve("key"),new byte[32]);
    }
    GyingBridge open(AssistantService assistant) {
        return new GyingBridge(assistant,mapper,origin(),directory.resolve("token").toString(),directory.resolve("private/state.bin").toString(),directory.resolve("key").toString());
    }
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]);String origin=args[1];ObjectMapper mapper=new ObjectMapper();
        AssistantService assistant=mock(AssistantService.class);
        when(assistant.run(any())).thenAnswer(call->{Files.writeString(dir.resolve("inference-marker"),"once");return mapper.createObjectNode().put("scope","gying").put("answer","process-answer");});
        var bridge=new GyingBridge(assistant,mapper,origin,dir.resolve("token").toString(),dir.resolve("private/state.bin").toString(),dir.resolve("key").toString());
        bridge.pollOnce();Runtime.getRuntime().halt(0);
    }
    @Test void realProcessExitRestoresResultWithoutRepeatingInference() throws Exception {
        serve();AssistantService assistant=mock(AssistantService.class);
        try {
            String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var child=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                    GyingBridgeRecoveryTest.class.getName(),directory.toString(),origin()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("process.log").toFile()).start();
            boolean exited=child.waitFor(30,TimeUnit.SECONDS);if(!exited)child.destroy();
            assertThat(exited).isTrue();assertThat(child.exitValue()).isZero();
            assertThat(Files.readString(directory.resolve("inference-marker"))).isEqualTo("once");
            assertThat(deliveries).hasValue(1);deliveryStatus.set(200);
            var restored=open(assistant);restored.pollOnce();restored.close();
            verifyNoInteractions(assistant);assertThat(claims).hasValue(1);assertThat(deliveries).hasValue(2);
            assertThat(received.get().path("result").path("answer").asText()).isEqualTo("process-answer");
        } finally {server.stop(0);}
    }
    @Test void failedCheckpointBlocksInferenceAndDelivery() throws Exception {
        serve();AssistantService assistant=mock(AssistantService.class);
        var bridge=open(assistant);
        try {
            Files.createDirectory(directory.resolve("private/state.bin"));
            bridge.pollOnce();bridge.pollOnce();verifyNoInteractions(assistant);
            assertThat(claims).hasValue(1);assertThat(deliveries).hasValue(0);
        } finally {bridge.close();server.stop(0);}
    }
    @Test void resultSurvivesRestartAndRetriesDeliveryWithoutInferenceOrClaim() throws Exception {
        serve();AssistantService assistant=mock(AssistantService.class);
        when(assistant.run(any())).thenReturn(mapper.createObjectNode().put("scope","gying").put("answer","fixture-answer"));
        try {
            var first=open(assistant);first.pollOnce();assertThat(deliveries).hasValue(1);first.close();
            deliveryStatus.set(200);var restored=open(assistant);restored.pollOnce();restored.close();
            verify(assistant,times(1)).run(any());assertThat(claims).hasValue(1);assertThat(deliveries).hasValue(2);
            assertThat(received.get().path("result").path("answer").asText()).isEqualTo("fixture-answer");
            try(var state=new EncryptedAiState(mapper,directory.resolve("private/state.bin").toString(),directory.resolve("key").toString())) {
                assertThat(state.read().isEmpty()).isTrue();
            }
        } finally {server.stop(0);}
    }
    @Test void interruptedAttemptIsReturnedAsFailureAndNeverInferredAgain() throws Exception {
        serve();AssistantService assistant=mock(AssistantService.class);
        try {
            var pending=mapper.createObjectNode().put("jobId",id).put("lease",lease).put("retainUntil",Instant.now().plusSeconds(900).toString());
            try(var state=new EncryptedAiState(mapper,directory.resolve("private/state.bin").toString(),directory.resolve("key").toString())) {
                state.write(mapper.createObjectNode().put("version",1).put("origin",origin()+"/").set("pending",pending));
            }
            deliveryStatus.set(200);var restored=open(assistant);restored.pollOnce();restored.close();
            verifyNoInteractions(assistant);assertThat(claims).hasValue(0);
            assertThat(received.get().path("result").path("error").path("code").asText()).isEqualTo("BRIDGE_INTERRUPTED");
        } finally {server.stop(0);}
    }
    @Test void leaseCanRenewWhileInferenceRuns() throws Exception {
        serve();AssistantService assistant=mock(AssistantService.class);
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        when(assistant.run(any())).thenAnswer(call->{entered.countDown();if(!finish.await(10,TimeUnit.SECONDS))throw new IllegalStateException();return mapper.createObjectNode().put("scope","gying").put("answer","ok");});
        var bridge=open(assistant);ExecutorService runner=Executors.newSingleThreadExecutor();
        try {
            var future=runner.submit(bridge::pollOnce);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            bridge.renewOnce();assertThat(renewals).hasValue(1);finish.countDown();future.get(5,TimeUnit.SECONDS);
        } finally {finish.countDown();runner.shutdown();bridge.close();server.stop(0);}
    }
    @Test void conflictingServerLeaseDiscardsOldReceiptWithoutDeliveringIt() throws Exception {
        serve();AssistantService assistant=mock(AssistantService.class);
        when(assistant.run(any())).thenReturn(mapper.createObjectNode().put("scope","gying").put("answer","ok"));
        var bridge=open(assistant);
        try {
            bridge.pollOnce();renewStatus.set(409);bridge.renewOnce();bridge.pollOnce();
            assertThat(deliveries).hasValue(1);verify(assistant,times(1)).run(any());
        } finally {bridge.close();server.stop(0);}
    }
    @Test void corruptCheckpointPreventsClaimAndInference() throws Exception {
        serve();AssistantService assistant=mock(AssistantService.class);
        try {
            var bridge=open(assistant);bridge.close();Path file=directory.resolve("private/state.bin");Files.write(file,new byte[64]);
            assertThatThrownBy(()->open(assistant)).isInstanceOf(IllegalStateException.class);
            assertThat(claims).hasValue(0);verifyNoInteractions(assistant);
        } finally {server.stop(0);}
    }
}
