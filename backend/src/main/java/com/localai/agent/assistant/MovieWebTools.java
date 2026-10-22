package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed public encyclopedia API; the model never chooses a host or follows page links. */
@Service
public class MovieWebTools {
    private static final int MAX_BYTES=128*1024;
    private final ObjectMapper mapper;
    private final OkHttpClient client;
    @Autowired public MovieWebTools(ObjectMapper mapper) {
        this(mapper,new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(3,TimeUnit.SECONDS).readTimeout(6,TimeUnit.SECONDS)
                .callTimeout(8,TimeUnit.SECONDS).build());
    }
    MovieWebTools(ObjectMapper mapper,OkHttpClient client) {this.mapper=mapper;this.client=client;}
    @PreDestroy public void close() {client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    public ArrayNode definitions() {
        var result=mapper.createArrayNode();var function=result.addObject().put("type","function").putObject("function");
        function.put("name","searchMovieWeb").put("description","联网搜索电影剧情/主题/演员百科资料。来源不是片库，不证明有资源。query用影片名或简短剧情关键词。");
        function.set("parameters",mapper.valueToTree(Map.of("type","object","properties",Map.of(
                "query",Map.of("type","string","minLength",1,"maxLength",120),
                "language",Map.of("type","string","enum",List.of("zh","en"))),"required",List.of("query"),"additionalProperties",false)));
        return result;
    }
    public ObjectNode execute(String name,JsonNode args) {
        if(!"searchMovieWeb".equals(name))return error("TOOL_NOT_ALLOWED","当前工具仅用于联网查询影视资料");
        if(args==null||!args.isObject())return error("INVALID_ARGUMENTS","参数必须为 JSON 对象");
        var fields=args.fieldNames();while(fields.hasNext())if(!Set.of("query","language").contains(fields.next()))return error("INVALID_ARGUMENTS","参数包含未知字段");
        String query=args.path("query").asText("").trim(),language=args.path("language").asText("zh");
        if(!args.path("query").isTextual()||query.isBlank()||query.length()>120||query.chars().anyMatch(c->c<32||c==127)
                ||query.matches("(?is).*(?:https?://|file:|magnet:|转存|发布|执行|删除|password|token|cookie).*")
                ||!Set.of("zh","en").contains(language)||args.has("language")&&!args.path("language").isTextual())
            return error("INVALID_ARGUMENTS","仅支持 1–120 字符的影视关键词及 zh/en 语言");
        HttpUrl endpoint=HttpUrl.get("https://"+language+".wikipedia.org/w/api.php").newBuilder()
                .addQueryParameter("action","query").addQueryParameter("format","json").addQueryParameter("formatversion","2")
                .addQueryParameter("generator","search").addQueryParameter("gsrsearch",query).addQueryParameter("gsrnamespace","0")
                .addQueryParameter("gsrlimit","1").addQueryParameter("prop","extracts|info").addQueryParameter("inprop","url")
                .addQueryParameter("explaintext","1").addQueryParameter("exchars","2200").addQueryParameter("exlimit","1").build();
        try {
            JsonNode body=get(endpoint);if(body.has("error"))return error("WEB_INVALID_RESPONSE","百科查询未完成");
            JsonNode pages=body.path("query").path("pages");
            if(!pages.isArray()&&!body.has("batchcomplete"))return error("WEB_INVALID_RESPONSE","百科返回无效查询数据");
            ObjectNode data=mapper.createObjectNode().put("source",endpoint.toString()).put("queriedAt",Instant.now().toString())
                    .put("provider","wikipedia").put("externalLinksVerifiedNow",false);
            ArrayNode results=data.putArray("webResults");
            for(JsonNode page:pages) {
                if(results.size()==2)break;
                String title=page.path("title").asText(""),url=page.path("fullurl").asText(""),extract=page.path("extract").asText("");
                HttpUrl source=HttpUrl.parse(url);
                if(title.isBlank()||extract.isBlank()||source==null||!source.scheme().equals("https")||!source.host().equals(language+".wikipedia.org")
                        ||!source.username().isEmpty()||!source.password().isEmpty())continue;
                String snippet=excerpt(extract);
                results.addObject().put("title",title.substring(0,Math.min(100,title.length()))).put("url",source.toString())
                        .put("snippet",snippet).put("license","CC BY-SA; Wikipedia contributors").put("partial",extract.length()>snippet.length());
            }
            data.put("returned",results.size()).put("truncated",true);
            return mapper.createObjectNode().put("ok",true).set("data",data);
        } catch(InterruptedIOException e) {return error("WEB_TIMEOUT","联网百科查询超时");}
        catch(IOException e) {return error("WEB_UNAVAILABLE","联网百科查询暂不可用");}
    }
    private String excerpt(String text) {
        // Prefer the actual plot section over production/cast headings when available.
        int start=0;for(String label:List.of("== 劇情 ==","== 剧情 ==","== 故事 ==","== Plot ==")) {
            int found=text.indexOf(label);if(found>=0){start=found+label.length();break;}
        }
        String value=text.substring(start).replaceAll("==+[^=]*==+"," ").replaceAll("\\s+"," ").trim();
        return value.substring(0,Math.min(480,value.length()));
    }
    private JsonNode get(HttpUrl endpoint)throws IOException {
        Request request=new Request.Builder().url(endpoint).get().header("Accept","application/json")
                .header("User-Agent","GYingMovieResearch/1.0 (read-only movie encyclopedia lookup)").build();
        try(Response response=client.newCall(request).execute()) {
            if(!response.isSuccessful()||response.body()==null)throw new IOException();
            ResponseBody body=response.body();if(body.contentLength()>MAX_BYTES)throw new IOException();
            byte[] bytes=body.byteStream().readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IOException();
            JsonNode parsed=mapper.readTree(bytes);if(parsed==null||!parsed.isObject())throw new IOException();return parsed;
        }
    }
    private ObjectNode error(String code,String message) {ObjectNode node=mapper.createObjectNode().put("ok",false);node.putObject("error").put("code",code).put("message",message);return node;}
}
