package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.localai.agent.ollama.OllamaService;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GyingResearchServiceTest {
    final ObjectMapper mapper=new ObjectMapper();MovieQueryPlanner planner;SemanticMovieIndex index;GyingCatalogClient catalog;GyingTools details;MovieSearchTools web;OllamaService model;GyingResearchService service;
    @BeforeEach void setup()throws Exception{
        planner=mock(MovieQueryPlanner.class);index=mock(SemanticMovieIndex.class);catalog=mock(GyingCatalogClient.class);details=mock(GyingTools.class);web=mock(MovieSearchTools.class);model=mock(OllamaService.class);
        when(model.researchModelName()).thenReturn("fixture");when(model.structuredFits(any())).thenReturn(true);when(index.candidates(any(),anyLong())).thenReturn(mapper.createArrayNode());service=new GyingResearchService(planner,index,catalog,details,web,model,mapper);
    }
    ObjectNode plan(String json)throws Exception{return (ObjectNode)mapper.readTree(json);}
    ObjectNode found(ObjectNode p,String id){var f=mapper.createObjectNode().put("answer","原答案").put("total",1);f.set("queryPlan",p);var movies=f.putArray("movies");if(id!=null)movies.addObject().put("id",id).put("titleCn","样本").put("summary","宇航员寻找返回地球的航线。");return f;}
    ObjectNode run(){return service.run(mapper.createObjectNode().put("message","宇航员回家电影"),"job","lease",System.nanoTime()+60_000_000_000L);}
    @Test void invalidPlanFailureRetainsDistinctModelCode()throws Exception{
        when(planner.plan(any(),anyLong())).thenThrow(new OllamaService.OllamaException("CONTEXT_TOO_LONG","fixture"));assertThat(run().path("error").path("code").asText()).isEqualTo("CONTEXT_TOO_LONG");verifyNoInteractions(catalog,web);
    }
    @Test void supportedCandidatesHaveActualQuoteAndCorrectCounts()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any())).thenReturn(f);
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"selectedIds\":[\"a\",\"invented\"],\"quotes\":[{\"id\":\"a\",\"quote\":\"返回地球的航线\"}]}"));
        var r=run();assertThat(r.path("status").asText()).isEqualTo("completed");assertThat(f.path("returned").asInt()).isEqualTo(1);assertThat(f.path("movies").get(0).path("reasons").toString()).contains("返回地球的航线");verifyNoInteractions(web);
    }
    @Test void inventedQuoteCannotPassCandidateReviewAndRealWebFailureIsPreserved()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any())).thenReturn(f);
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"selectedIds\":[\"a\"],\"quotes\":[{\"id\":\"a\",\"quote\":\"完全虚构的情节\"}]}"));when(web.search(anyString())).thenReturn(plan("{\"ok\":false,\"error\":{\"code\":\"WEB_TIMEOUT\"}}"));
        assertThat(run().path("error").path("code").asText()).isEqualTo("WEB_TIMEOUT");assertThat(f.path("movies").isEmpty()).isTrue();verify(web).search(anyString());
    }
    @Test void emptyCatalogSearchUsesActualSearchTool()throws Exception{
        var p=plan("{\"intent\":\"search\"}");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any())).thenReturn(found(p,null));when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        var r=run();assertThat(r.path("status").asText()).isEqualTo("completed");assertThat(r.path("trace").toString()).contains("searchMovieWeb");
    }
    @Test void irrelevantWebResultsAreRemovedInsteadOfDisplayed()throws Exception{
        var p=plan("{\"intent\":\"web\"}");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any())).thenReturn(found(p,null));var response=plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"网红同名\",\"snippet\":\"无关资料\",\"url\":\"https://example.com\"}]}}");when(web.search(anyString())).thenReturn(response);when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[],\"identifiedTitle\":\"\"}"));
        assertThat(run().path("answer").asText()).contains("没有足够相关");assertThat(response.path("data").path("webResults").isEmpty()).isTrue();
    }
    @Test void webLocatedTitleRetainsOriginalHardConditionsAndSupersedesOldResults()throws Exception{
        var p=plan("{\"intent\":\"web\",\"yearFrom\":2020,\"excludedDirectors\":[\"甲\"]}");var first=found(p,null);var second=found(p,"b");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any())).thenReturn(first,second);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"样本电影\",\"snippet\":\"样本的剧情\"}]}}"));when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[0],\"identifiedTitle\":\"样本\",\"quotes\":[{\"index\":0,\"quote\":\"样本的剧情\"}]}"));
        var r=run();var captor=org.mockito.ArgumentCaptor.forClass(ObjectNode.class);verify(catalog,times(2)).query(captor.capture());var actual=captor.getAllValues().get(1).path("plan");assertThat(actual.path("yearFrom").asInt()).isEqualTo(2020);assertThat(actual.path("excludedDirectors").get(0).asText()).isEqualTo("甲");assertThat(first.path("superseded").asBoolean()).isTrue();assertThat(r.path("answer").asText()).contains("保留原筛选条件");
    }
    @Test void webConfirmationOfAnAlreadyLocatedTitlePreservesItsPlotAnswer()throws Exception{
        var p=plan("{\"intent\":\"web\",\"titles\":[\"样本\"]}");var f=found(p,"a");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any())).thenReturn(f);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"样本电影\",\"snippet\":\"宇航员寻找返回地球的航线。\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[0],\"identifiedTitle\":\"样本\",\"quotes\":[{\"index\":0,\"quote\":\"返回地球的航线\"}]}"));
        var r=run();assertThat(r.path("answer").asText()).contains("片库简介", "返回地球的航线");assertThat(r.path("queryPlan").path("intent").asText()).isEqualTo("web");assertThat(f.path("superseded").asBoolean()).isFalse();verify(catalog).query(any());
    }
    @Test void explicitSourceIdentityMismatchIsRemovedBeforeModelJudgement()throws Exception{
        var p=plan("{\"intent\": \"web\", \"titles\": [\"样本\"]}");var f=found(p,"a");((ObjectNode)f.path("movies").get(0)).put("category","mv").put("year",2001);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any())).thenReturn(f);
        when(web.search(anyString())).thenReturn(plan("{\"ok\": true, \"data\": {\"webResults\": [{\"title\": \"样本 (2001)\", \"snippet\": \"返回地球的航线\"}, {\"title\": \"样本 (TV series)\", \"snippet\": \"同名电视剧的故事\"}, {\"title\": \"样本 (1999 film)\", \"snippet\": \"不同年份的重拍版本\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\": [0], \"identifiedTitle\": \"样本\", \"quotes\": [{\"index\": 0, \"quote\": \"返回地球的航线\"}]}"));
        var r=run();var data=r.path("trace").get(2).path("result").path("data");
        assertThat(r.path("answer").asText()).contains("返回地球的航线").doesNotContain("同名电视剧","重拍版本");assertThat(data.path("identityFilteredCount").asInt()).isEqualTo(2);assertThat(data.path("webResults").size()).isEqualTo(1);
    }

}
