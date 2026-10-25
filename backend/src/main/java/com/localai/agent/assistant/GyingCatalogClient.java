package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

/** Bridge-authenticated fixed catalog endpoints; callers cannot choose URLs or user IDs. */
@Service
public class GyingCatalogClient {
    private final ObjectMapper mapper;private final HttpUrl origin;private final String tokenFile;
    // A cold public catalog refresh can exceed OkHttp's default 10-second read timeout.
    private final OkHttpClient client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(2,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build();
    public GyingCatalogClient(ObjectMapper mapper,@Value("${LOCAL_AI_GYING_BRIDGE_BASE_URL:${local-ai.gying.base-url:http://127.0.0.1:8880}}")String base,@Value("${LOCAL_AI_GYING_BRIDGE_TOKEN_FILE:}")String tokenFile){this.mapper=mapper;origin=GyingTools.checkedOrigin(base);this.tokenFile=tokenFile;}
    public ObjectNode query(ObjectNode body)throws IOException{return request("api/internal/ai/catalog/query",body);}
    public ObjectNode query(ObjectNode body,long deadlineNanos)throws IOException{return request("api/internal/ai/catalog/query",body,deadlineNanos);}
    public List<ObjectNode> documents()throws IOException{
        List<ObjectNode> docs=new ArrayList<>();for(int page=1;page<=500;page++){
            ObjectNode response=request("api/internal/ai/catalog/documents?page="+page+"&size=100",null);JsonNode items=response.path("documents");
            if(!items.isArray()||!response.path("total").isIntegralNumber())throw new IOException("Invalid catalog documents");
            for(JsonNode doc:items)if(doc.isObject())docs.add((ObjectNode)doc);
            if(items.isEmpty()||docs.size()>=response.path("total").asInt())break;
        }return List.copyOf(docs);
    }
    private ObjectNode request(String path,ObjectNode body)throws IOException{return request(path,body,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));}
    private ObjectNode request(String path,ObjectNode body,long deadlineNanos)throws IOException{
        if(deadlineNanos-System.nanoTime()<=0)throw new java.io.InterruptedIOException("Catalog request deadline expired");
        if(tokenFile.isBlank())throw new IOException("Catalog bridge is not configured");String token=Files.readString(Path.of(tokenFile)).trim();
        HttpUrl.Builder url=origin.newBuilder();String[] parts=path.split("\\?",2);url.addPathSegments(parts[0]);if(parts.length>1)url.encodedQuery(parts[1]);
        Request.Builder request=new Request.Builder().url(url.build()).header("X-AI-Bridge-Token",token);
        if(body!=null)request.post(RequestBody.create(body.toString(),MediaType.get("application/json")));
        Call call=client.newCall(request.build());call.timeout().deadlineNanoTime(deadlineNanos);
        try(Response response=call.execute()){
            if(!response.isSuccessful()||response.body()==null)throw new IOException("Catalog query failed (HTTP "+response.code()+")");
            byte[] bytes=response.body().byteStream().readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new IOException("Catalog response too large");
            JsonNode result=mapper.readTree(bytes);if(!result.isObject())throw new IOException("Invalid catalog response");return (ObjectNode)result;
        }
    }
    @PreDestroy public void close(){client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
}
