package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.junit.jupiter.api.*;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
class MovieWebToolsTest {
    final ObjectMapper mapper=new ObjectMapper();MovieWebTools tools;AtomicReference<Request> request=new AtomicReference<>();
    volatile int status=200;volatile String payload="{\"batchcomplete\":true,\"query\":{\"pages\":[{\"title\":\"测试电影\",\"fullurl\":\"https://zh.wikipedia.org/wiki/Test\",\"extract\":\"一部电影。\\n== 剧情 ==\\n侦探调查悬案。\"}]}}";
    volatile boolean timeout;
    @BeforeEach void setup() {
        tools=new MovieWebTools(mapper,new OkHttpClient.Builder().addInterceptor(chain->{
            request.set(chain.request());if(timeout)throw new InterruptedIOException();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .body(ResponseBody.create(payload,MediaType.get("application/json"))).build();}).build());
    }
    @AfterEach void close(){tools.close();}
    ObjectNode args(){return mapper.createObjectNode().put("query","悬疑电影");}
    @Test void fixedHttpsSearchReturnsActualSourceAndPlotExcerpt() {
        var output=tools.execute("searchMovieWeb",args());assertThat(output.path("ok").asBoolean()).isTrue();
        assertThat(request.get().method()).isEqualTo("GET");assertThat(request.get().url().host()).isEqualTo("zh.wikipedia.org");
        assertThat(request.get().url().queryParameter("gsrsearch")).isEqualTo("悬疑电影");
        var source=output.path("data").path("webResults").get(0);
        assertThat(source.path("url").asText()).isEqualTo("https://zh.wikipedia.org/wiki/Test");
        assertThat(source.path("snippet").asText()).isEqualTo("侦探调查悬案。");
        assertThat(output.path("data").has("movies")).isFalse();
    }
    @Test void urlsUnknownFieldsAndInvalidLanguagesNeverMakeRequests() {
        for(var bad:java.util.List.of(args().put("url","http://127.0.0.1"),args().put("query","http://internal/secret"),args().put("language","../"),args().put("query",12))) {
            assertThat(tools.execute("searchMovieWeb",bad).path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        }assertThat(request.get()).isNull();
    }
    @Test void sourceLinksMustRemainOnTheFixedPublicHost() {
        payload=payload.replace("https://zh.wikipedia.org/wiki/Test","http://127.0.0.1/secret");
        assertThat(tools.execute("searchMovieWeb",args()).path("data").path("webResults")).isEmpty();
    }
    @Test void redirectsTimeoutMalformedJsonAndOversizedBodiesAreFailures() {
        status=302;assertThat(tools.execute("searchMovieWeb",args()).path("ok").asBoolean()).isFalse();
        status=200;payload="invalid";assertThat(tools.execute("searchMovieWeb",args()).path("ok").asBoolean()).isFalse();
        payload="x".repeat(129*1024);assertThat(tools.execute("searchMovieWeb",args()).path("ok").asBoolean()).isFalse();
        timeout=true;assertThat(tools.execute("searchMovieWeb",args()).path("error").path("code").asText()).isEqualTo("WEB_TIMEOUT");
    }
    @Test void emptyResultsStayEmptyAndDoNotInventMovies() {
        payload="{\"batchcomplete\":true}";var output=tools.execute("searchMovieWeb",args());
        assertThat(output.path("ok").asBoolean()).isTrue();assertThat(output.path("data").path("webResults")).isEmpty();
    }
}
