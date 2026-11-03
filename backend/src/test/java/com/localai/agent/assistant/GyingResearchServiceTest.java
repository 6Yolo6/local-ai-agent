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
    ObjectNode candidateReview(){ObjectNode review=mapper.createObjectNode();review.putArray("assessments");return review;}
    ObjectNode assessment(ObjectNode review,String id,String quote,String... statuses){
        ObjectNode item=review.withArray("assessments").addObject().put("id",id);ArrayNode checks=item.putArray("checks");for(int i=0;i<statuses.length;i++)checks.addObject().put("requirement",i).put("status",statuses[i]).put("quote",quote);return review;
    }
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
        when(model.structured(any(),any(),anyLong())).thenReturn(assessment(assessment(candidateReview(),"a","返回地球的航线","supported"),"invented","凭空补充","supported"));
        var r=run();assertThat(r.path("status").asText()).isEqualTo("completed");assertThat(f.path("returned").asInt()).isEqualTo(1);assertThat(f.path("movies").get(0).path("reasons").toString()).contains("返回地球的航线");verifyNoInteractions(web);
    }
    @Test void inventedQuoteCannotPassCandidateReviewAndRealWebFailureIsPreserved()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(model.structured(any(),any(),anyLong())).thenReturn(assessment(candidateReview(),"a","完全虚构的情节","supported"));when(web.search(anyString())).thenReturn(plan("{\"ok\":false,\"error\":{\"code\":\"WEB_TIMEOUT\"}}"));
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


    @Test void ordinaryCatalogAnswerListsActualCandidatesInOrderAndNeverSubstitutesMissingRequestedScore()throws Exception{
        var p=plan("{\"intent\":\"search\",\"ratingSource\":\"douban\",\"sort\":\"rating_desc\",\"limit\":2,\"minRuntime\":91,\"maxRuntime\":99}");
        var f=found(p,"a");f.put("answer","按本次已识别条件找到 357 部，先展示 2 部。");
        ((ObjectNode)f.path("movies").get(0)).put("titleCn","高分样本").put("year",2009).put("runtimeMinutes",96).put("doubanScore",9.1).put("imdbScore",9.8).put("rating",9.8);
        ((ArrayNode)f.path("movies")).addObject().put("id","b").put("titleCn","缺分样本").put("runtimeMinutes",95).putNull("doubanScore").put("imdbScore",9.9).put("rating",9.9);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        var r=service.run(mapper.createObjectNode().put("message","找喜剧电影，片长严格超过90分钟且严格少于100分钟，最多两部，按豆瓣评分排序，缺分写暂无，不用IMDb补"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("status").asText()).isEqualTo("completed");
        assertThat(r.path("answer").asText()).contains("1. 《高分样本》；年份：2009；片长：96分钟；豆瓣评分：9.1","2. 《缺分样本》；年份：暂无；片长：95分钟；豆瓣评分：暂无").doesNotContain("9.8","9.9","IMDb评分");
        verifyNoInteractions(details,web);verify(model,never()).structured(any(),any(),anyLong());
    }
    @Test void unspecifiedRatingSourceDisplaysActualProviderScoresRatherThanAnAmbiguousAggregate()throws Exception{
        var p=plan("{\"intent\":\"search\"}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("titleCn","").put("titleEn","Sample").put("doubanScore",7.1).put("imdbScore",8.2).put("tmdbVoteAverage",6.3).put("rating",9.9);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        String answer=run().path("answer").asText();
        assertThat(answer).contains("《Sample》","片长：暂无","豆瓣评分：7.1","IMDb评分：8.2","TMDB评分：6.3").doesNotContain("9.9");
    }
    @Test void catalogAnswerOnlyListsCandidatesRetainedByActualSummaryReview()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("titleCn","已核对样本");
        ((ArrayNode)f.path("movies")).addObject().put("id","b").put("titleCn","被拒绝样本").put("summary","城里的厨师参加比赛。");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(model.structured(any(),any(),anyLong())).thenReturn(assessment(assessment(candidateReview(),"a","返回地球的航线","supported"),"b","凭空补充的宇航员","supported"));
        var r=run();
        assertThat(r.path("answer").asText()).contains("1. 《已核对样本》","剧情资料依据：“返回地球的航线”").doesNotContain("被拒绝样本","凭空补充");
        verifyNoInteractions(web,details);
    }
    @Test void originalLanguageCandidatesRemainIndividuallyUnverifiedAfterEmptyWebResults()throws Exception{
        var p=plan("{\"intent\":\"search\",\"languagesAny\":[\"中文\",\"西班牙语\"],\"unsupportedConditions\":[\"片库语言字段未区分原始对白与发行配音，原始语言条件仍待核验\"]}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("titleCn","国语样本").put("runtimeMinutes",90).putArray("languages").add("国语");
        ((ArrayNode)f.path("movies")).addObject().put("id","b").put("titleCn","语言缺失样本");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        var r=service.run(mapper.createObjectNode().put("message","找动画电影，原始对白中文或西班牙语，只有配音资料就标未核验"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("《国语样本》","片库语言：国语；原始对白：未核验","《语言缺失样本》","片库语言：暂无资料；原始对白：未核验","尚不能视为满足全部硬条件");
        assertThat(r.path("queryPlan").path("unsupportedConditions").size()).isEqualTo(1);
    }
    @Test void catalogListSurvivesWebRelevanceFailureWithoutShowingUnreviewedSources()throws Exception{
        var p=plan("{\"intent\":\"search\",\"unsupportedConditions\":[\"内容限制未核验\"]}");var f=found(p,"a");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"未核对资料\",\"snippet\":\"未核对摘要\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenThrow(new IOException("fixture"));
        var r=run();
        assertThat(r.path("status").asText()).isEqualTo("completed");assertThat(r.path("partial").asBoolean()).isTrue();
        assertThat(r.path("answer").asText()).contains("1. 《样本》","内容限制未核验","联网资料未完成相关性核对").doesNotContain("未核对资料","未核对摘要");
    }
    @Test void webLocatedReplacementListsNewActualCandidateWithoutKeepingSupersededCandidate()throws Exception{
        var p=plan("{\"intent\":\"web\",\"yearFrom\":2020}");var first=found(p,"a");var second=found(p,"b");
        ((ObjectNode)first.path("movies").get(0)).put("titleCn","旧候选");((ObjectNode)second.path("movies").get(0)).put("titleCn","新候选").put("year",2021);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(first,second);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[{\"title\":\"新候选电影\",\"snippet\":\"新候选的剧情资料\"}]}}"));
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"relevantIndices\":[0],\"identifiedTitle\":\"新候选\",\"quotes\":[{\"index\":0,\"quote\":\"新候选的剧情资料\"}]}"));
        var r=run();
        assertThat(r.path("answer").asText()).contains("保留原筛选条件","1. 《新候选》；年份：2021").doesNotContain("旧候选");assertThat(first.path("superseded").asBoolean()).isTrue();
    }
    @Test void emptyCatalogAnswerDoesNotInventAListAndCatalogRowsRemainBounded()throws Exception{
        var p=plan("{\"intent\":\"search\"}");var empty=found(p,null);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(empty);when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        assertThat(run().path("answer").asText()).doesNotContain("1. 《","样本");
        var many=found(p,null);for(int i=1;i<=21;i++)((ArrayNode)many.path("movies")).addObject().put("id","id-"+i).put("titleCn","样本"+i);
        when(catalog.query(any(),anyLong())).thenReturn(many);
        assertThat(run().path("answer").asText()).contains("20. 《样本20》").doesNotContain("21. 《样本21》");
    }


    @Test void televisionRuntimeIsDisplayedWithoutAssumingSingleEpisodeOrWholeSeries()throws Exception{
        var p=plan("{\"intent\":\"search\",\"category\":\"tv\"}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("category","tv").put("titleCn","剧集样本").put("runtimeMinutes",45);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        var r=service.run(mapper.createObjectNode().put("message","找剧集，列出片库时长"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("《剧集样本》","片库时长（单集/整部口径未核验）：45分钟").doesNotContain("片长：45分钟");
        verifyNoInteractions(details,web);
    }


    @Test void literalButUnrelatedQuoteDoesNotProveTheRequiredCoreScene()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"电影院为核心场景\"}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("titleCn","避难所故事").put("summary","一家人生活在小镇，父亲在后院修建风暴避难所。");
        var review=assessment(candidateReview(),"a","一家人生活在小镇","unknown");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(review);
        when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        var r=service.run(mapper.createObjectNode().put("message","找以电影院为核心场景的电影"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("status").asText()).isEqualTo("completed");assertThat(r.path("answer").asText()).contains("没有足够剧情依据").doesNotContain("避难所故事","1. 《");
        assertThat(f.path("movies").isEmpty()).isTrue();assertThat(f.path("total").asInt()).isZero();verify(model).structured(any(),any(),anyLong());
    }
    @Test void everyRequiredThemeMustBeSupportedButUnknownSoftPreferenceDoesNotRemoveAGroundedCandidate()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"小镇电影院为核心场景，最好结尾温暖\",\"limit\":2}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("titleCn","放映故事").put("summary","小镇的电影院是主人公成长的核心场景，居民每天在这里看电影。");
        ((ArrayNode)f.path("movies")).addObject().put("id","b").put("titleCn","归属故事").put("summary","小镇女孩照顾病人，两人重新找到人生与心灵的归属。");
        var review=candidateReview();
        assessment(review,"a","小镇的电影院是主人公成长的核心场景","supported","unknown");
        assessment(review,"b","小镇女孩照顾病人","unknown","supported");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(review);
        var r=service.run(mapper.createObjectNode().put("message","找小镇里以电影院为核心场景的电影，最好结尾温暖但仅是偏好"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("1. 《放映故事》","偏好未核验：结尾温暖").doesNotContain("归属故事","2. 《");
        assertThat(f.path("returned").asInt()).isEqualTo(1);verifyNoInteractions(web,details);verify(model).structured(any(),any(),anyLong());
    }
    @Test void aContradictedSoftPreferenceIsExplainedWithoutRelaxingTheCoreScene()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"电影院为核心场景\"}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("summary","电影院是两人生活的核心场景，结局却令人悲伤。");
        var review=assessment(candidateReview(),"a","电影院是两人生活的核心场景","supported","contradicted");((ObjectNode)review.path("assessments").get(0).path("checks").get(1)).put("quote","结局却令人悲伤");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(review);
        var r=service.run(mapper.createObjectNode().put("message","找以电影院为核心场景的电影，最好结尾温暖，这只是偏好"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("1. 《样本》","与偏好不完全一致：结尾温暖");verifyNoInteractions(web);
    }
    @Test void missingRequirementCoverageCannotBeHiddenBehindARealQuote()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");
        var review=assessment(candidateReview(),"a","宇航员寻找返回地球的航线","supported");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(review);when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        assertThat(service.run(mapper.createObjectNode().put("message","找宇航员回家电影，但空间站必须是核心场景"),"job","lease",System.nanoTime()+60_000_000_000L).path("answer").asText()).doesNotContain("1. 《");assertThat(f.path("movies").isEmpty()).isTrue();
    }
    @Test void legacySelectedIdsAndQuotesWithoutRequirementChecksFailClosed()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(model.structured(any(),any(),anyLong())).thenReturn(plan("{\"selectedIds\":[\"a\"],\"quotes\":[{\"id\":\"a\",\"quote\":\"返回地球的航线\"}]}"));when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        assertThat(run().path("answer").asText()).doesNotContain("1. 《");assertThat(f.path("movies").isEmpty()).isTrue();
    }
    @Test void unseenExcerptAndRenamedRequirementCannotPassCandidateReview()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("summary","背景资料。".repeat(65)+"宇航员寻找返回地球的航线。");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(assessment(candidateReview(),"a","返回地球的航线","supported"));when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        assertThat(run().path("answer").asText()).doesNotContain("1. 《");assertThat(f.path("movies").isEmpty()).isTrue();
        var second=found(p,"a");var renamed=assessment(candidateReview(),"a","返回地球的航线","supported");((ObjectNode)renamed.path("assessments").get(0).path("checks").get(0)).put("requirement","宇航员");when(catalog.query(any(),anyLong())).thenReturn(second);when(model.structured(any(),any(),anyLong())).thenReturn(renamed);
        assertThat(run().path("answer").asText()).doesNotContain("1. 《");assertThat(second.path("movies").isEmpty()).isTrue();
    }
    @Test void duplicateCandidateAssessmentsCannotOverrideAnUnknownCoreRequirement()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");
        var review=assessment(assessment(candidateReview(),"a","返回地球的航线","supported"),"a","返回地球的航线","unknown");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(review);when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        assertThat(run().path("answer").asText()).doesNotContain("1. 《");assertThat(f.path("movies").isEmpty()).isTrue();
    }
    @Test void requirementChecksKeepCatalogOrderAndExistingEightCandidateBudget()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\",\"limit\":2,\"ratingSource\":\"douban\"}");var f=found(p,null);f.put("total",9);
        for(int i=1;i<=9;i++)f.withArray("movies").addObject().put("id","id-"+i).put("titleCn","候选"+i).put("summary","宇航员寻找返回地球的航线。").put("doubanScore",10.0-i);
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);
        when(model.structured(any(),any(),anyLong())).thenAnswer(invocation->{JsonNode messages=invocation.getArgument(0);JsonNode candidates=mapper.readTree(messages.get(1).path("content").asText()).path("candidates");var review=candidateReview();for(int i=candidates.size()-1;i>=0;i--)assessment(review,candidates.get(i).path("id").asText(),"返回地球的航线","supported");return review;});
        var r=run();assertThat(r.path("answer").asText()).contains("1. 《候选1》","2. 《候选2》").doesNotContain("候选3");assertThat(f.path("truncated").asBoolean()).isTrue();assertThat(f.path("recalledTotal").asInt()).isEqualTo(9);verify(model,times(2)).structured(any(),any(),anyLong());verifyNoInteractions(web,details);
    }

    @Test void serverLocksTheCompleteCoreAndCurrentStrengtheningWhileRemovingAnExplicitlyWaivedPreference()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"以小镇电影院为故事重要场景的剧情电影，最好结尾温暖\"}");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("summary","小镇的电影院是孩子们生活的中心，他们每天都来这里看电影。");
        var review=assessment(candidateReview(),"a","小镇的电影院是孩子们生活的中心","supported","supported");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(review);
        var r=service.run(mapper.createObjectNode().put("message","结尾不用温暖也行，但电影院必须是核心场景，电影类型和最多三部都别改。"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).contains("1. 《样本》").doesNotContain("偏好未核验：结尾温暖");
        var messages=org.mockito.ArgumentCaptor.forClass(ArrayNode.class);var schema=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(model).structured(messages.capture(),schema.capture(),anyLong());
        JsonNode requirements=mapper.readTree(messages.getValue().get(1).path("content").asText()).path("requirements");
        assertThat(requirements.size()).isEqualTo(2);assertThat(requirements.get(0).path("text").asText()).isEqualTo("以小镇电影院为故事重要场景的剧情电影");assertThat(requirements.get(1).path("text").asText()).contains("电影院必须是核心场景");
        assertThat(schema.getValue().path("properties").has("requirements")).isFalse();verifyNoInteractions(web,details);
    }
    @Test void preferenceClauseDoesNotDiscardLaterCoreRelationship()throws Exception{
        var p=mapper.createObjectNode().put("intent","search").put("semanticText","小镇电影院，最好轻松，围绕放映员与观众关系");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("summary","小镇电影院的放映员与观众共同分享电影与人生。");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(assessment(candidateReview(),"a","小镇电影院的放映员与观众共同分享电影与人生","supported","unknown"));
        var r=service.run(mapper.createObjectNode().put("message","继续找上述主题的电影"),"job","lease",System.nanoTime()+60_000_000_000L);
        var messages=org.mockito.ArgumentCaptor.forClass(ArrayNode.class);verify(model).structured(messages.capture(),any(),anyLong());
        JsonNode requirements=mapper.readTree(messages.getValue().get(1).path("content").asText()).path("requirements");
        assertThat(requirements.size()).isEqualTo(2);assertThat(requirements.get(0).path("text").asText()).isEqualTo("小镇电影院；围绕放映员与观众关系");assertThat(requirements.get(1).path("text").asText()).isEqualTo("轻松");
        assertThat(r.path("answer").asText()).contains("1. 《样本》","偏好未核验：轻松");verifyNoInteractions(web,details);
    }
    @Test void inlinePreferencesLeaveLaterUnrelatedCoreClausesAndCollectMultipleSoftClauses()throws Exception{
        var p=mapper.createObjectNode().put("intent","search").put("semanticText","远洋航行最好节奏舒缓；船员之间的信任，优先开放式结尾，突出共同决策");var f=found(p,"a");
        ((ObjectNode)f.path("movies").get(0)).put("summary","远洋航行中的船员相互信任，遇到风暴时共同决策。");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(assessment(candidateReview(),"a","远洋航行中的船员相互信任，遇到风暴时共同决策","supported","unknown"));
        service.run(mapper.createObjectNode().put("message","继续找上述主题的电影"),"job","lease",System.nanoTime()+60_000_000_000L);
        var messages=org.mockito.ArgumentCaptor.forClass(ArrayNode.class);verify(model).structured(messages.capture(),any(),anyLong());
        JsonNode requirements=mapper.readTree(messages.getValue().get(1).path("content").asText()).path("requirements");
        assertThat(requirements.size()).isEqualTo(2);assertThat(requirements.get(0).path("text").asText()).isEqualTo("远洋航行；船员之间的信任；突出共同决策");assertThat(requirements.get(1).path("text").asText()).isEqualTo("节奏舒缓；开放式结尾");
        verifyNoInteractions(web,details);
    }
    @Test void eachMandatoryRequirementNeedsItsOwnObservedQuote()throws Exception{
        var p=plan("{\"intent\":\"search\",\"semanticText\":\"宇航员回家\"}");var f=found(p,"a");
        var review=assessment(candidateReview(),"a","返回地球的航线","supported","supported");((ObjectNode)review.path("assessments").get(0).path("checks").get(1)).put("quote","空间站是核心场景");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(review);when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        var r=service.run(mapper.createObjectNode().put("message","找宇航员回家电影，但空间站必须是核心场景"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).doesNotContain("1. 《");assertThat(f.path("movies").isEmpty()).isTrue();
    }
    @Test void similarIntentWithoutSemanticTextKeepsTheGivenSeedAndDoesNotInventAComparableTheme()throws Exception{
        var p=plan("{\"intent\":\"similar\",\"seedTitle\":\"星际归途\",\"semanticText\":\"\"}");var f=found(p,"a");
        when(planner.plan(any(),anyLong())).thenReturn(p);when(catalog.query(any(),anyLong())).thenReturn(f);when(model.structured(any(),any(),anyLong())).thenReturn(assessment(candidateReview(),"a","","unknown"));when(web.search(anyString())).thenReturn(plan("{\"ok\":true,\"data\":{\"webResults\":[]}}"));
        var r=service.run(mapper.createObjectNode().put("message","找类似《星际归途》的电影"),"job","lease",System.nanoTime()+60_000_000_000L);
        assertThat(r.path("answer").asText()).doesNotContain("1. 《");var messages=org.mockito.ArgumentCaptor.forClass(ArrayNode.class);verify(model).structured(messages.capture(),any(),anyLong());
        JsonNode input=mapper.readTree(messages.getValue().get(1).path("content").asText());assertThat(input.path("requirements").get(0).path("text").asText()).contains("星际归途");assertThat(input.path("plan").path("seedTitle").asText()).isEqualTo("星际归途");
    }
}
