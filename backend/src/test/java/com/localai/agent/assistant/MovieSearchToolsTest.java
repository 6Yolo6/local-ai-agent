package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class MovieSearchToolsTest {
 final ObjectMapper mapper=new ObjectMapper();
 MovieSearchTools tool(String html,AtomicInteger calls){return new MovieSearchTools(mapper,new OkHttpClient.Builder().addInterceptor(chain->{calls.incrementAndGet();assertThat(chain.request().url().host()).isEqualTo("www.bing.com");assertThat(chain.request().url().queryParameter("format")).isNull();return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(html,MediaType.get("text/html"))).build();}).build());}
 String item(String url,String title){return "<li class='b_algo'><h2><a href='"+url+"'>"+title+"</a></h2><div><p>影片&lt;剧情&gt;资料</p></div></li>";}
 @Test void realSearchStructureFiltersDomainsDuplicatesAndNavigation(){var calls=new AtomicInteger();var t=tool("<html><body><a href='https://imdb.com'>导航</a><ol>"+item("https://zh.wikipedia.org/wiki/Film","电影资料")+item("https://wikipedia.org.evil.test/","恶意同名域")+item("https://zh.wikipedia.org/wiki/Film","重复")+"</ol></body></html>",calls);try{var r=t.search("宇航员回家的电影");assertThat(calls.get()).isEqualTo(1);assertThat(r.path("data").path("webResults").size()).isEqualTo(1);assertThat(r.path("data").path("provider").asText()).isEqualTo("bing-html");assertThat(r.path("data").path("webResults").get(0).path("snippet").asText()).contains("影片<剧情>资料");}finally{t.close();}}
 @Test void bingCitationIsDecodedWithoutFollowingOrFetchingIt(){String url="https://en.wikipedia.org/wiki/Amadeus_(film)";String encoded=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));var calls=new AtomicInteger();var t=tool("<ol>"+item("https://www.bing.com/ck/a?u=a1"+encoded+"&amp;p=fixture","Amadeus (film)")+"</ol>",calls);try{assertThat(t.search("Amadeus film plot").path("data").path("webResults").get(0).path("url").asText()).isEqualTo(url);assertThat(calls.get()).isEqualTo(1);}finally{t.close();}}
 @Test void rejectsCitationCredentialsNonstandardPortsAndBrokenRedirects(){var t=tool("<ol>"+item("https://secret@imdb.com/","凭据")+item("https://imdb.com:444/","端口")+item("https://www.bing.com/ck/a?u=bad","错误编码")+"</ol>",new AtomicInteger());try{assertThat(t.search("电影").path("data").path("webResults").isEmpty()).isTrue();}finally{t.close();}}
 @Test void invalidUrlQueryDoesNotMakeAnyRequest(){var calls=new AtomicInteger();var t=tool("",calls);try{assertThat(t.search("https://example.com").path("ok").asBoolean()).isFalse();assertThat(calls.get()).isZero();}finally{t.close();}}
 @Test void oversizedResponseFailsExplicitly(){var t=tool(" ".repeat(1048577),new AtomicInteger());try{assertThat(t.search("电影").path("error").path("code").asText()).isEqualTo("WEB_RESPONSE_TOO_LARGE");}finally{t.close();}}
}
