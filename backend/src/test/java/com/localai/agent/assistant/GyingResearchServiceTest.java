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
    @Test void catalogRetrievalKeepsTheOriginalRequestDeadline()throws Exception{
        var p=plan("{\"intent\":\"search\"}");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(found(p,"a"));
        long deadline=System.nanoTime()+60_000_000_000L;
        service.run(mapper.createObjectNode().put("message","科幻电影"),"job","lease",deadline);
        verify(catalog).query(any(),eq(deadline));
    }
    @Test void invalidPlanFailureRetainsDistinctModelCode()throws Exception{
        when(planner.plan(any(),anyLong())).thenThrow(new OllamaService.OllamaException("CONTEXT_TOO_LONG","fixture"));assertThat(run().path("error").path("code").asText()).isEqualTo("CONTEXT_TOO_LONG");verifyNoInteractions(catalog,web);
    }
    @Test void supportedCandidatesHaveActualQuoteAndCorrectCounts()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"selectedIds\":[\"a\",\"invented\"],\"quotes\":[{\"id\":\"a\",\"quote\":\"返回地球的航线\"}]}"));
        var r=run();assertThat(r.path("status").asText()).isEqualTo("completed");assertThat(f.path("returned").asInt()).isEqualTo(1);assertThat(f.path("movies").get(0).path("reasons").toString()).contains("返回地球的航线");verifyNoInteractions(web);
    }
    @Test void inventedQuoteCannotPassCandidateReviewAndRealWebFailureIsPreserved()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"selectedIds\":[\"a\"],\"quotes\":[{\"id\":\"a\",\"quote\":\"完全虚构的情节\"}]}"));when(web.search(anyString())).thenReturn(plan("{\"ok\":false,\"error\":{\"code\":\"WEB_TIMEOUT\"}}"));
        assertThat(run().path("error").path("code").asText()).isEqualTo("WEB_TIMEOUT");assertThat(f.path("movies").isEmpty()).isTrue();verify(web).search(anyString());
    }
    @Test void emptyCatalogSearchUsesActualSearchTool()throws Exception{
        var p=plan("{\"intent\":\"search\"}");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(found(p,null));when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        var r=run();assertThat(r.path("status").asText()).isEqualTo("completed");assertThat(r.path("trace").toString()).contains("searchMovieWeb");
    }
    @Test void effectiveCatalogClarificationDoesNotGuessFromTheWebOrFetchADifferentFilm()throws Exception{
        var p=plan("{\"intent\":\"details\",\"ordinal\":1,\"inheritContext\":true}");
        var effective=plan("{\"intent\":\"clarify\",\"clarification\":\"上一轮没有可用影片，请补充片名。\"}");
        var response=found(effective,null);response.put("answer","上一轮没有可用影片，请补充片名。");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(response);
        var r=run();
        assertThat(r.path("status").asText()).isEqualTo("completed");
        assertThat(r.path("answer").asText()).contains("上一轮没有可用影片");
        assertThat(r.path("queryPlan").path("intent").asText()).isEqualTo("clarify");
        assertThat(r.path("trace").toString()).contains("queryCatalog");
        verifyNoInteractions(web,details);
    }
    @Test void irrelevantWebResultsAreRemovedInsteadOfDisplayed()throws Exception{
        var p=plan("{\"intent\":\"web\"}");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(found(p,null));var response=plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"网红同名\",\"snippet\":\"无关资料\",\"url\":\"https://example.com\"}]}}");when(web.search(anyString())).thenReturn(response);when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[],\"identifiedTitle\":\"\"}"));
        assertThat(run().path("answer").asText()).contains("没有足够相关");assertThat(response.path("data").path("webResults").isEmpty()).isTrue();
    }
    @Test void webLocatedTitleRetainsOriginalHardConditionsAndSupersedesOldResults()throws Exception{
        var p=plan("{\"intent\":\"web\",\"yearFrom\":2020,\"excludedDirectors\":[\"甲\"]}");var first=found(p,null);var second=found(p,"b");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(first,second);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"样本电影\",\"snippet\":\"样本的剧情\"}]}}"));when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[0],\"identifiedTitle\":\"样本\",\"quotes\":[{\"index\":0,\"quote\":\"样本的剧情\"}]}"));
        var r=run();var captor=org.mockito.ArgumentCaptor.forClass(ObjectNode.class);verify(catalog,times(2)).query(captor.capture(),anyLong());var actual=captor.getAllValues().get(1).path("plan");assertThat(actual.path("yearFrom").asInt()).isEqualTo(2020);assertThat(actual.path("excludedDirectors").get(0).asText()).isEqualTo("甲");assertThat(first.path("superseded").asBoolean()).isTrue();assertThat(r.path("answer").asText()).contains("保留原筛选条件");
    }
    @Test void webConfirmationOfAnAlreadyLocatedTitlePreservesItsPlotAnswer()throws Exception{
        var p=plan("{\"intent\":\"web\",\"titles\":[\"样本\"]}");var f=found(p,"a");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"样本电影\",\"snippet\":\"宇航员寻找返回地球的航线。\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[0],\"identifiedTitle\":\"样本\",\"quotes\":[{\"index\":0,\"quote\":\"返回地球的航线\"}]}"));
        var r=run();assertThat(r.path("answer").asText()).contains("片库简介", "返回地球的航线");assertThat(r.path("queryPlan").path("intent").asText()).isEqualTo("web");assertThat(f.path("superseded").asBoolean()).isFalse();verify(catalog).query(any(),anyLong());
    }
    @Test void explicitSourceIdentityMismatchIsRemovedBeforeModelJudgement()throws Exception{
        var p=plan("{\"intent\": \"web\", \"titles\": [\"样本\"]}");var f=found(p,"a");((ObjectNode)f.path("movies").get(0)).put("category","mv").put("year",2001);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(web.search(anyString())).thenReturn(plan("{\"ok\": true, \"data\": {\"webResults\": [{\"title\": \"样本 (2001)\", \"snippet\": \"返回地球的航线\"}, {\"title\": \"样本 (TV series)\", \"snippet\": \"同名电视剧的故事\"}, {\"title\": \"样本 (1999 film)\", \"snippet\": \"不同年份的重拍版本\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\": [0], \"identifiedTitle\": \"样本\", \"quotes\": [{\"index\": 0, \"quote\": \"返回地球的航线\"}]}"));
        var r=run();var data=r.path("trace").get(2).path("result").path("data");
        assertThat(r.path("answer").asText()).contains("返回地球的航线").doesNotContain("同名电视剧","重拍版本");assertThat(data.path("identityFilteredCount").asInt()).isEqualTo(2);assertThat(data.path("webResults").size()).isEqualTo(1);
    }

    @Test void exactFilmIdentityLinkIsKeptWithoutClaimingTheSnippetVerifiesItsPlot()throws Exception{
        var p=plan("{\"intent\": \"web\", \"titles\": [\"样本\"]}");var f=found(p,"a");((ObjectNode)f.path("movies").get(0)).put("category","mv").put("year",2001).put("titleEn","Sample");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(web.search(anyString())).thenReturn(plan("{\"ok\": true, \"data\": {\"webResults\": [{\"title\": \"Sample (2001) - IMDb\", \"url\": \"https://www.imdb.com/title/tt0000001/\", \"snippet\": \"Directed by a director. Cast: two actors.\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\": [], \"identifiedTitle\": \"\", \"quotes\": []}"));
        var r=run();var data=r.path("trace").get(2).path("result").path("data");
        assertThat(r.path("answer").asText()).contains("片库简介","联网定位到同一影片条目","剧情来自片库","未验证剧情");assertThat(data.path("rawReturned").asInt()).isEqualTo(1);assertThat(data.path("returned").asInt()).isEqualTo(1);assertThat(data.path("evidenceScope").asText()).isEqualTo("film-identity");verify(catalog).query(any(),anyLong());
    }


    @Test void versionEnumerationListsOnlyActualYearsAndDirectorsIncludingUnknownMetadata()throws Exception{
        var p=plan("{\"intent\":\"search\",\"titles\":[\"旧城\"]}");var f=found(p,"a");f.put("total",2);
        ((ObjectNode)f.path("movies").get(0)).put("titleCn","旧城").put("year",1994).putArray("directors").add("导演甲");
        ((ArrayNode)f.path("movies")).addObject().put("id","b").put("titleCn","旧城").put("year",2019);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        var r=service.run(mapper.createObjectNode().put("message","《旧城》有几个不同电影版本？请列出年份和导演"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("年份：1994；导演：导演甲","年份：2019；导演：暂无资料");verifyNoInteractions(details,web);
    }
    @Test void requestedDirectorAndActorsAreReturnedFromTheSameLocatedFilm()throws Exception{
        var p=plan("{\"intent\":\"details\",\"titles\":[\"雨港\"],\"yearFrom\":2019,\"yearTo\":2019}");var f=found(p,"a");f.put("selectedId","a");
        var tool=plan("{\"ok\":true,\"data\":{\"movie\":{\"id\":\"a\",\"titleCn\":\"雨港\",\"year\":2019,\"directors\":[\"导演甲\"],\"actors\":[\"演员乙\",\"演员丙\"]}}}");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(details.execute(eq("getMovieDetails"),any())).thenReturn(tool);
        var r=service.run(mapper.createObjectNode().put("message","请查2019年版《雨港》的导演和主演"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("2019","导演：导演甲","主演：演员乙、演员丙");verify(details).execute("getMovieDetails",mapper.createObjectNode().put("id","a"));verifyNoInteractions(web);
        ((ObjectNode)tool.path("data").path("movie")).put("id","other-version");
        r=service.run(mapper.createObjectNode().put("message","查《雨港》的导演"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("status").asText()).isEqualTo("error");assertThat(r.path("error").path("code").asText()).isEqualTo("CATALOG_DETAILS_IDENTITY_MISMATCH");assertThat(r.path("answer").asText()).doesNotContain("导演甲");
    }
    @Test void ordinarySearchDisplaysExplicitlyRequestedYearWithoutInventingMissingYear()throws Exception{
        var p=plan("{\"intent\":\"search\"}");var f=found(p,"a");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        var r=service.run(mapper.createObjectNode().put("message","找一部电影，列出年份"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("样本","年份：暂无");verifyNoInteractions(details,web);
    }
    @Test void announcementUsesActualQuestionAndRejectsFilmIdentityAsAnnouncementEvidence()throws Exception{
        var p=plan("{\"intent\":\"web\",\"titles\":[\"旧城\"]}");var f=found(p,"a");((ObjectNode)f.path("movies").get(0)).put("titleCn","旧城").put("titleEn","Old City").put("category","mv").put("year",2019);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"Old City (2019) - IMDb\",\"snippet\":\"Directed by a director.\",\"url\":\"https://www.imdb.com/title/tt0000001/\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[],\"identifiedTitle\":\"\",\"quotes\":[]}"));
        String question="截至今天，《旧城》是否有官方重映公告？请给出来源和发布日期";
        var r=service.run(mapper.createObjectNode().put("message",question),"job","lease",System.nanoTime()+60_000_000_000L);
        verify(web).search(question);assertThat(r.path("answer").asText()).contains("本次查询日期：","官方公告依据","发布日期仍待核验").doesNotContain("片库简介","宇航员","联网定位到同一影片");verify(catalog).query(any(),anyLong());
        assertThat(r.path("trace").get(2).path("result").path("data").path("webResults").isEmpty()).isTrue();
    }
    @Test void announcementSourceYearIsNotMistakenForFilmVersionOrUsedToRequeryTheCatalog()throws Exception{
        var p=plan("{\"intent\":\"web\",\"titles\":[\"旧城\"]}");var f=found(p,"a");((ObjectNode)f.path("movies").get(0)).put("titleCn","旧城").put("year",1994).put("category","mv");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"旧城官方重映公告 (2026)\",\"snippet\":\"官方宣布旧城重映，公告日期仍需查证。\",\"url\":\"https://example.com/announcement\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[0],\"identifiedTitle\":\"旧城\",\"quotes\":[{\"index\":0,\"quote\":\"官方宣布旧城重映\"}]}"));
        var r=service.run(mapper.createObjectNode().put("message","查《旧城》是否有官方重映公告及来源日期"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("官方宣布旧城重映","公告发布日期仍未知").doesNotContain("保留原筛选条件重新查询");verify(catalog).query(any(),anyLong());
        assertThat(r.path("trace").get(2).path("result").path("data").path("webResults").size()).isEqualTo(1);
    }
    @Test void unsupportedSceneRestrictionIsVisibleWhenOnlyCatalogCandidatesCanBeReturned()throws Exception{
        var p=plan("{\"intent\":\"search\",\"genresAll\":[\"纪录片\"],\"unsupportedConditions\":[\"片库普通简介不能逐场核验镜头内容和伤害排除条件，需要完整内容指南核验\"]}");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(found(p,"a"));when(web.search(anyString())).thenReturn(plan("{\"ok\":false,\"error\":{\"code\":\"WEB_TIMEOUT\"}}"));
        var r=service.run(mapper.createObjectNode().put("message","找纪录电影，必须没有受伤镜头；无内容指南就不要保证"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("尚未核实的条件","不能逐场核验","尚不能视为满足全部硬条件");assertThat(r.path("partial").asBoolean()).isTrue();
    }


    @Test void explicitOriginalVoiceWebQueryKeepsActualQuestionAndUnverifiedLanguageNotice()throws Exception{
        var p=plan("{\"intent\":\"web\",\"titles\":[\"样本\"],\"unsupportedConditions\":[\"片库语言字段未区分原始对白与发行配音，原始语言条件仍待核验\"]}");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(found(p,"a"));when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        String question="联网查《样本》的原始对白是中文还是西班牙语，不要把发行配音当原声";
        var r=service.run(mapper.createObjectNode().put("message",question),"job","lease",System.nanoTime()+60_000_000_000L);
        verify(web).search(question);assertThat(r.path("answer").asText()).contains("未区分原始对白与发行配音","仍待核验","尚不能视为满足全部硬条件");
    }


    @Test void relatedWebEvidenceWithoutCatalogCandidateOrFilmTitleRetainsEveryUnverifiedCondition()throws Exception{
        var p=plan("{\"intent\":\"search\",\"genresAll\":[\"纪录片\"],\"unsupportedConditions\":[\"片库语言字段未区分原始对白与发行配音，原始语言条件仍待核验\",\"片库普通简介不能逐场核验镜头内容和伤害排除条件，需要完整内容指南核验\"]}");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(found(p,null));
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"徒步纪录片观影讨论\",\"snippet\":\"讨论了徒步题材的银幕语言与拍摄过程，并未给出具体片名或逐场内容指南。\",\"url\":\"https://example.com/discussion\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[0],\"identifiedTitle\":\"\",\"quotes\":[{\"index\":0,\"quote\":\"徒步题材的银幕语言\"}]}"));
        var r=service.run(mapper.createObjectNode().put("message","找中文原始对白的徒步纪录片，不含伤害画面，无逐场指南就不要保证"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("status").asText()).isEqualTo("completed");
        assertThat(r.path("answer").asText()).contains("不足以确认唯一影片","徒步题材的银幕语言","尚未核实的条件","未区分原始对白与发行配音","普通简介不能逐场核验","尚不能视为满足全部硬条件");
        assertThat(r.path("queryPlan").path("unsupportedConditions").size()).isEqualTo(2);
        verify(catalog).query(any(),anyLong());verify(web).search(anyString());verify(model).structured(any(),any(),anyLong());verifyNoInteractions(details);
    }

}
