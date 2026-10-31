package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localai.agent.ollama.OllamaService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExplicitQueryConstraintsTest {
    final ObjectMapper mapper=new ObjectMapper();
    final MovieQueryPlanner planner=new MovieQueryPlanner(mock(OllamaService.class),mapper);
    ObjectNode extract(String message,String proposed)throws Exception {
        return planner.grounded(mapper.createObjectNode().put("message",message),(ObjectNode)mapper.readTree(proposed));
    }
    ObjectNode followup(String message,String proposed)throws Exception {
        ObjectNode request=mapper.createObjectNode().put("message",message);
        request.putObject("context").putObject("queryPlan").put("yearFrom",2000).put("yearTo",2010).put("maxRuntime",110).put("limit",2).putArray("genresAll").add("音乐");
        return planner.grounded(request,(ObjectNode)mapper.readTree(proposed));
    }
    @Test void explicitCountsOverrideModelDefaultsAcrossOrdinaryListPhrasings()throws Exception {
        for(String text:new String[]{"给两部喜剧电影","最多两部剧情剧集","先给最多两部","推荐两部电影","列出两部","找电影，排前两"})
            assertThat(extract(text,"{\"intent\":\"search\",\"limit\":10}").path("limit").asInt()).as(text).isEqualTo(2);
        assertThat(extract("最多三部电影","{\"intent\":\"search\"}").path("limit").asInt()).isEqualTo(3);
        assertThat(extract("最多0部电影","{\"intent\":\"search\"}").path("intent").asText()).isEqualTo("clarify");
    }
    @Test void explicitScoresSortByTheChosenSourceWithoutInventingOrdering()throws Exception {
        for(String text:new String[]{"找电影，按豆瓣评分排序，不用IMDb补缺分","找剧集，按IMDb评分排序，豆瓣不用","按评分从高到低排前三"}) {
            var plan=extract(text,"{\"intent\":\"search\",\"sort\":\"relevance\"}");
            assertThat(plan.path("sort").asText()).as(text).isEqualTo("rating_desc");
        }
        assertThat(extract("找电影，按年份从旧到新排序","{\"intent\":\"search\",\"sort\":\"rating_desc\"}").path("sort").asText()).isEqualTo("year_asc");
        assertThat(extract("找电影","{\"intent\":\"search\",\"sort\":\"rating_desc\"}").has("sort")).isFalse();
        assertThat(extract("按豆瓣从低到高排序","{\"intent\":\"search\",\"sort\":\"rating_desc\"}").path("intent").asText()).isEqualTo("clarify");
    }
    @Test void runtimeOpenBoundsRejectEqualDurationsAndSupportHourConversion()throws Exception {
        var plan=extract("电影超过90分钟但少于100分钟，最多三部","{\"intent\":\"search\",\"minRuntime\":90,\"maxRuntime\":100,\"limit\":10}");
        assertThat(plan.path("minRuntime").asInt()).isEqualTo(91);
        assertThat(plan.path("maxRuntime").asInt()).isEqualTo(99);
        assertThat(plan.path("limit").asInt()).isEqualTo(3);
        plan=extract("超过1.5小时，低于两小时","{\"intent\":\"search\"}");
        assertThat(plan.path("minRuntime").asInt()).isEqualTo(91);
        assertThat(plan.path("maxRuntime").asInt()).isEqualTo(119);
        plan=extract("至少90.5分钟，最多99.5分钟","{\"intent\":\"search\"}");
        assertThat(plan.path("minRuntime").asInt()).isEqualTo(91);
        assertThat(plan.path("maxRuntime").asInt()).isEqualTo(99);
    }
    @Test void explicitlyImpossibleBoundsClarifyInsteadOfSendingAnInvalidCatalogPlan()throws Exception {
        for(String text:new String[]{"至少150分钟，同时不能超过100分钟","超过90分钟且少于91分钟"}) {
            var plan=extract(text,"{\"intent\":\"search\"}");
            assertThat(plan.path("intent").asText()).as(text).isEqualTo("clarify");
            assertThat(plan.path("clarification").asText()).contains("不能同时满足");
            assertThat(plan.has("minRuntime")).isFalse();assertThat(plan.has("maxRuntime")).isFalse();
        }
        var plan=extract("2008到2001年上映的电影","{\"intent\":\"search\"}");
        assertThat(plan.path("intent").asText()).isEqualTo("clarify");
        assertThat(plan.has("yearFrom")).isFalse();assertThat(plan.has("yearTo")).isFalse();
    }
    @Test void yearBoundsKeepInclusiveAndStrictMeaningsDistinct()throws Exception {
        assertThat(extract("找2000年后上映的电影","{\"intent\":\"search\"}").path("yearFrom").asInt()).isEqualTo(2001);
        assertThat(extract("找2000年以前的电影","{\"intent\":\"search\"}").path("yearTo").asInt()).isEqualTo(2000);
        var plan=extract("2015年及以后上映的电影","{\"intent\":\"search\",\"yearTo\":2010}");
        assertThat(plan.path("yearFrom").asInt()).isEqualTo(2015);assertThat(plan.has("yearTo")).isFalse();
        plan=extract("找1984年版的电影","{\"intent\":\"search\"}");
        assertThat(plan.path("yearFrom").asInt()).isEqualTo(1984);assertThat(plan.path("yearTo").asInt()).isEqualTo(1984);
    }
    @Test void rangeReplacementIsLiteralAndOrdinaryRefinementDoesNotClearTheOtherBoundary()throws Exception {
        var plan=followup("年份改成2015年及以后，其他条件不变，重新给两部","{\"intent\":\"search\",\"inheritContext\":false,\"yearTo\":2010,\"limit\":10}");
        assertThat(plan.path("inheritContext").asBoolean()).isTrue();
        assertThat(plan.path("yearFrom").asInt()).isEqualTo(2015);assertThat(plan.has("yearTo")).isFalse();
        assertThat(plan.path("replaceRanges")).containsExactly(mapper.getNodeFactory().textNode("year"));
        plan=followup("其中只留至少100分钟的","{\"intent\":\"search\",\"replaceRanges\":[\"runtime\",\"year\"],\"maxRuntime\":600}");
        assertThat(plan.path("inheritContext").asBoolean()).isTrue();assertThat(plan.path("scope").asText()).isEqualTo("previousResults");
        assertThat(plan.path("minRuntime").asInt()).isEqualTo(100);assertThat(plan.has("replaceRanges")).isFalse();assertThat(plan.has("maxRuntime")).isFalse();
        plan=followup("片长改为95至105分钟，其他条件不变","{\"intent\":\"search\"}");
        assertThat(plan.path("replaceRanges")).containsExactly(mapper.getNodeFactory().textNode("runtime"));
        plan=followup("评分改为豆瓣7.5到8.5分，其他条件不变","{\"intent\":\"search\"}");
        assertThat(plan.path("replaceRanges")).containsExactly(mapper.getNodeFactory().textNode("rating"));
    }
    @Test void knownConversationListOperationsInheritButFreshRequestsRemainIndependent()throws Exception {
        for(String text:new String[]{"年份改成2015年及以后，其他条件不变","再按豆瓣评分排序","按年份从旧到新排","不要甲乙丙导演的"})
            assertThat(followup(text,"{\"intent\":\"search\"}").path("inheritContext").asBoolean()).as(text).isTrue();
        assertThat(followup("找几部新的喜剧电影","{\"intent\":\"search\",\"inheritContext\":true}").path("inheritContext").asBoolean()).isFalse();
    }
    @Test void genresIncludeDramaSeriesMusicAndMusicalsWithoutConfusingPlotQuestions()throws Exception {
        var plan=extract("不要电影，找剧情剧集，最多两部","{\"intent\":\"search\"}");
        assertThat(plan.path("category").asText()).isEqualTo("tv");assertThat(plan.path("genresAll")).containsExactly(mapper.getNodeFactory().textNode("剧情"));
        assertThat(extract("找音乐电影","{\"intent\":\"search\"}").path("genresAll")).containsExactly(mapper.getNodeFactory().textNode("音乐"));
        assertThat(extract("找歌舞电影","{\"intent\":\"search\"}").path("genresAll")).containsExactly(mapper.getNodeFactory().textNode("歌舞"));
        assertThat(extract("介绍《样片》的剧情","{\"intent\":\"details\",\"genresAll\":[\"剧情\"]}").path("genresAll").isEmpty()).isTrue();
    }
    @Test void quotedTitleNumbersProvidersLanguagesAndGenresSupplyNoConditions()throws Exception {
        var plan=extract("查《豆瓣8分喜剧日语1999年后90分钟给两部》的详情","{\"intent\":\"search\",\"genresAll\":[\"喜剧\"],\"languagesAny\":[\"日语\"],\"yearFrom\":1999,\"minRuntime\":90,\"minRating\":8,\"ratingSource\":\"douban\",\"limit\":2}");
        for(String field:new String[]{"yearFrom","yearTo","minRuntime","maxRuntime","minRating","ratingSource","sort"})assertThat(plan.has(field)).as(field).isFalse();
        assertThat(plan.path("genresAll").isEmpty()).isTrue();assertThat(plan.path("languagesAny").isEmpty()).isTrue();
        assertThat(plan.path("limit").asInt()).isEqualTo(10);
    }
    @Test void referencesToOlderUnverifiedListsClarifyInsteadOfSelectingTheLatestOrdinal()throws Exception {
        for(String text:new String[]{"最开始那份列表的第一部导演是谁？","第一轮里的第一部主演是谁？","最早那份列表的第二部的故事是什么？"}) {
            var plan=followup(text,"{\"intent\":\"details\",\"ordinal\":1}");
            assertThat(plan.path("intent").asText()).as(text).isEqualTo("clarify");
            assertThat(plan.has("ordinal")).isFalse();assertThat(plan.has("movieId")).isFalse();
            assertThat(plan.path("clarification").asText()).contains("更早列表");
        }
    }
    @Test void endpointsOutsideTheCatalogContractClarifyWithoutSendingInvalidNumbers()throws Exception {
        for(String text:new String[]{"至少900分钟的电影","超过600分钟的电影","小于1分钟的电影"}) {
            var plan=extract(text,"{\"intent\":\"search\"}");
            assertThat(plan.path("intent").asText()).as(text).isEqualTo("clarify");
            assertThat(plan.has("minRuntime")).isFalse();assertThat(plan.has("maxRuntime")).isFalse();
            assertThat(plan.path("clarification").asText()).contains("1至600分钟");
        }
        var plan=followup("片长改成至少900分钟，其他条件不变","{\"intent\":\"search\"}");
        assertThat(plan.path("intent").asText()).isEqualTo("clarify");assertThat(plan.has("replaceRanges")).isFalse();
        plan=extract("找1900年之前的电影","{\"intent\":\"search\"}");
        assertThat(plan.path("intent").asText()).isEqualTo("clarify");assertThat(plan.has("yearTo")).isFalse();
        plan=extract("找豆瓣高于10分的电影","{\"intent\":\"search\"}");
        assertThat(plan.path("intent").asText()).isEqualTo("clarify");assertThat(plan.has("minRating")).isFalse();
    }

    @Test void explicitlySearchingAgainAfterReplacementDoesNotRemainBoundToTheOldList()throws Exception {
        var plan=followup("年份改成2015年及以后，其他条件不变，重新给两部","{\"intent\":\"search\",\"scope\":\"previousResults\"}");
        assertThat(plan.path("scope").asText()).isEqualTo("catalog");
        plan=followup("只在刚才结果中，年份改成2005年及以后，重新给两部","{\"intent\":\"search\",\"scope\":\"catalog\"}");
        assertThat(plan.path("scope").asText()).isEqualTo("previousResults");
    }

    @Test void completeLargeNumbersClarifyInsteadOfDroppingOrTruncatingTheCondition()throws Exception {
        String huge="9".repeat(200);
        for(String value:new String[]{"1000",huge}) {
            var plan=extract("至少"+value+"分钟的电影","{\"intent\":\"search\"}");
            assertThat(plan.path("intent").asText()).as(value).isEqualTo("clarify");
            assertThat(plan.has("minRuntime")).isFalse();assertThat(plan.has("maxRuntime")).isFalse();
            assertThat(plan.path("clarification").asText()).contains("1至600分钟");
            plan=extract("最多"+value+"部电影","{\"intent\":\"search\",\"limit\":10}");
            assertThat(plan.path("intent").asText()).isEqualTo("clarify");assertThat(plan.has("limit")).isFalse();
            assertThat(plan.path("clarification").asText()).contains("1至20部");
        }
        for(String value:new String[]{"100",huge,"10.000000000000000000000001"}) {
            var plan=extract("豆瓣"+value+"分的电影","{\"intent\":\"search\",\"minRating\":10}");
            assertThat(plan.path("intent").asText()).as(value).isEqualTo("clarify");
            assertThat(plan.has("minRating")).isFalse();assertThat(plan.has("maxRating")).isFalse();
            assertThat(plan.path("clarification").asText()).contains("0至10分");
        }
        for(String value:new String[]{"1800",huge}) {
            var plan=extract("找"+value+"年以后上映的电影","{\"intent\":\"search\"}");
            assertThat(plan.path("intent").asText()).isEqualTo("clarify");assertThat(plan.has("yearFrom")).isFalse();
        }
        var plan=extract("找2100年版的电影","{\"intent\":\"search\"}");
        assertThat(plan.path("yearFrom").asInt()).isEqualTo(2100);assertThat(plan.path("yearTo").asInt()).isEqualTo(2100);
    }
    @Test void replacementDefaultsToCatalogWithoutRequiringTheUserToRepeatSearchAgain()throws Exception {
        var plan=followup("年份改成2015年及以后，其他条件不变","{\"intent\":\"search\",\"scope\":\"previousResults\"}");
        assertThat(plan.path("scope").asText()).isEqualTo("catalog");
        plan=followup("只在刚才结果中，年份改成2005年及以后","{\"intent\":\"search\",\"scope\":\"catalog\"}");
        assertThat(plan.path("scope").asText()).isEqualTo("previousResults");
    }
    @Test void quotedTitleRoutingAndOrdinalWordsNeverSelectAContraryIntentOrPreviousId()throws Exception {
        ObjectNode request=mapper.createObjectNode().put("message","查《第1部》的剧情");
        request.putObject("context").putArray("candidateIds").add("previous-first");
        var plan=planner.grounded(request,(ObjectNode)mapper.readTree("{\"intent\":\"search\",\"ordinal\":1}"));
        assertThat(plan.has("ordinal")).isFalse();assertThat(plan.has("movieId")).isFalse();
        assertThat(plan.path("titles")).containsExactly(mapper.getNodeFactory().textNode("第1部"));
        assertThat(plan.path("inheritContext").asBoolean()).isFalse();
        plan=extract("查《类似爱情》的剧情","{\"intent\":\"similar\",\"seedTitle\":\"类似爱情\"}");
        assertThat(plan.path("intent").asText()).isEqualTo("search");assertThat(plan.has("seedTitle")).isFalse();
        assertThat(plan.path("titles")).containsExactly(mapper.getNodeFactory().textNode("类似爱情"));
        plan=extract("查《网上查》的剧情","{\"intent\":\"web\"}");
        assertThat(plan.path("intent").asText()).isEqualTo("search");
        assertThat(extract("联网查《网上查》的剧情","{\"intent\":\"search\"}").path("intent").asText()).isEqualTo("web");
        plan=extract("查《样片》的官方上映公告","{\"intent\":\"web\",\"unsupportedConditions\":[\"官方上映公告\"]}");
        assertThat(plan.path("intent").asText()).isEqualTo("web");
        assertThat(plan.path("unsupportedConditions")).contains(mapper.getNodeFactory().textNode("官方上映公告"));
        assertThat(extract("找像《样片》的电影","{\"intent\":\"search\"}").path("intent").asText()).isEqualTo("similar");
    }

    @Test void invalidOrdinalAndSignedAmountsClarifyRatherThanOverflowOrLoseTheSign()throws Exception {
        for(String value:new String[]{"0","100","9".repeat(200)}) {
            var plan=followup("第"+value+"部主演是谁？","{\"intent\":\"details\",\"ordinal\":1}");
            assertThat(plan.path("intent").asText()).as(value).isEqualTo("clarify");assertThat(plan.has("ordinal")).isFalse();assertThat(plan.has("movieId")).isFalse();
        }
        for(String text:new String[]{"找少于-5分钟的电影","最多-5部电影","豆瓣-5分的电影","找-2000年版的电影"}) {
            var plan=extract(text,"{\"intent\":\"search\"}");
            assertThat(plan.path("intent").asText()).as(text).isEqualTo("clarify");
        }
        var plan=extract("今年想看时长95到120分钟的电影","{\"intent\":\"search\"}");
        assertThat(plan.path("minRuntime").asInt()).isEqualTo(95);assertThat(plan.path("maxRuntime").asInt()).isEqualTo(120);
        assertThat(plan.has("yearFrom")).isFalse();assertThat(plan.has("yearTo")).isFalse();
        plan=extract("时长95到120分钟，2000到2010年上映的电影","{\"intent\":\"search\"}");
        assertThat(plan.path("yearFrom").asInt()).isEqualTo(2000);assertThat(plan.path("yearTo").asInt()).isEqualTo(2010);
    }

}
