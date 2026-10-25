package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import okhttp3.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import jakarta.annotation.PreDestroy;

/** Fixed search endpoint. Parse bounded HTML locally; citation URLs are never fetched. */
@Service
public class MovieSearchTools {
    private final ObjectMapper mapper;private final OkHttpClient client;
    private static final Set<String> SOURCES=Set.of("wikipedia.org","imdb.com","themoviedb.org","douban.com","baidu.com","qq.com","iqiyi.com","youku.com","bilibili.com","netflix.com","justwatch.com","rottentomatoes.com","variety.com","hollywoodreporter.com","disneyplus.com","hulu.com","primevideo.com","bbc.com","mubi.com","1905.com","mtime.com","movieinsider.com");
    public MovieSearchTools(ObjectMapper mapper){this(mapper,new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(3,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).build());}
    MovieSearchTools(ObjectMapper mapper,OkHttpClient client){this.mapper=mapper;this.client=client;}
    public ObjectNode search(String query){
        if(query==null||query.isBlank()||query.length()>160||query.chars().anyMatch(c->c<32)||query.matches("(?is).*(?:https?://|file:|magnet:|password|cookie|token).*"))return error("INVALID_ARGUMENTS","请输入简短影视搜索词");
        String keyword=query.matches("(?is).*(?:电影|影片|影视|剧集|电视剧|动漫|film|movie|series).*")?query:query+" film";
        HttpUrl url=HttpUrl.get("https://www.bing.com/search").newBuilder().addQueryParameter("q",keyword).addQueryParameter("setlang","en-US").build();
        try(Response response=client.newCall(new Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (compatible; GYingMovieResearch/2.0)").build()).execute()){
            if(!response.isSuccessful()||response.body()==null)return error("WEB_UNAVAILABLE","联网搜索暂不可用");
            byte[] bytes=response.body().byteStream().readNBytes(1048577);if(bytes.length>1048576)return error("WEB_RESPONSE_TOO_LARGE","搜索响应超出范围");
            ObjectNode data=mapper.createObjectNode().put("provider","bing-html").put("source","https://www.bing.com/search").put("query",keyword).put("queriedAt",Instant.now().toString()).put("externalLinksVerifiedNow",false);
            ArrayNode results=data.putArray("webResults");parse(new String(bytes,StandardCharsets.UTF_8),results);
            return mapper.createObjectNode().put("ok",true).set("data",data.put("returned",results.size()).put("truncated",true));
        }catch(java.io.InterruptedIOException e){return error("WEB_TIMEOUT","联网搜索超时");}catch(Exception e){return error("WEB_UNAVAILABLE","联网搜索暂不可用");}
    }
    private static String citation(String href){
        HttpUrl url=HttpUrl.parse(href);if(url==null)return null;
        if(url.host().equals("www.bing.com")&&url.encodedPath().equals("/ck/a")){
            String encoded=url.queryParameter("u");if(encoded==null||!encoded.startsWith("a1")||encoded.length()>4096)return null;
            try{url=HttpUrl.parse(new String(Base64.getUrlDecoder().decode(encoded.substring(2)),StandardCharsets.UTF_8));}catch(IllegalArgumentException e){return null;}
        }
        if(url==null||!Set.of("http","https").contains(url.scheme())||!url.username().isBlank()||!url.password().isBlank()||url.port()!=(url.isHttps()?443:80))return null;
        HttpUrl checked=url;if(SOURCES.stream().noneMatch(h->checked.host().equals(h)||checked.host().endsWith("."+h)))return null;
        return url.newBuilder().fragment(null).build().toString();
    }
    void parse(String html,ArrayNode results)throws IOException{
        Set<String> seen=new HashSet<>();
        new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback(){
            int depth=0;boolean heading=false,paragraph=false;String href="";StringBuilder title=new StringBuilder(),snippet=new StringBuilder();
            @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attributes,int position){
                if(tag==HTML.Tag.LI){if(depth>0)depth++;else if(Arrays.asList(Objects.toString(attributes.getAttribute(HTML.Attribute.CLASS),"").split("\\s+")).contains("b_algo")){depth=1;title.setLength(0);snippet.setLength(0);href="";}}
                if(depth==0)return;if(tag==HTML.Tag.H2)heading=true;if(tag==HTML.Tag.P)paragraph=true;
                if(heading&&tag==HTML.Tag.A&&href.isBlank())href=Objects.toString(attributes.getAttribute(HTML.Attribute.HREF),"");
            }
            @Override public void handleText(char[] data,int position){if(depth>0){if(heading&&title.length()<1000)title.append(data);if(paragraph&&snippet.length()<2000)snippet.append(data).append(' ');}}
            @Override public void handleEndTag(HTML.Tag tag,int position){
                if(depth==0)return;if(tag==HTML.Tag.H2)heading=false;if(tag==HTML.Tag.P)paragraph=false;
                if(tag==HTML.Tag.LI&&--depth==0){String url=citation(href),name=clean(title.toString(),120);if(url!=null&&!name.isBlank()&&seen.add(url)&&results.size()<6)results.addObject().put("title",name).put("url",url).put("snippet",clean(snippet.toString(),320)).put("partial",true);heading=false;paragraph=false;}
            }
        },true);
    }
    private static String clean(String text,int max){String value=text.replaceAll("\\s+"," ").trim();return value.substring(0,Math.min(value.length(),max));}
    private ObjectNode error(String code,String message){return mapper.createObjectNode().put("ok",false).set("error",mapper.createObjectNode().put("code",code).put("message",message));}
    @PreDestroy public void close(){client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
}
