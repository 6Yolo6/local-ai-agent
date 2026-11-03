package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded server candidate rounds are evidence; submitted history strings are never identity evidence. */
final class TrustedCandidateHistory {
    private TrustedCandidateHistory() {}
    static final String OLDER_CLARIFICATION="当前可核对的候选上下文只有最新一轮，无法确定您指的更早列表中的影片。请提供那部的片名或那份列表，再核对对应资料。";
    private static final Pattern FIRST=Pattern.compile("最开始|第一轮|最早那份|第一份(?:列表|名单|结果)");
    private static final Pattern ORDINAL=Pattern.compile("第([一二两三四五六七八九十0-9]+)部");
    private static final Pattern ID=Pattern.compile("(?i)(?:\\b(?:movie\\s*)?id\\b|编号|标识)\\s*[:：=]?\\s*([A-Za-z0-9_-]{1,64})(?![A-Za-z0-9_-])");

    static void validate(JsonNode context) {
        if(!context.isObject()||context.toString().length()>10000)throw new IllegalArgumentException("无效的片库上下文");
        context.fieldNames().forEachRemaining(field->{if(!Set.of("queryPlan","candidateIds","candidates","candidateHistory","historyComplete","expiresAt").contains(field))throw new IllegalArgumentException("上下文包含未知字段");});
        if(context.has("queryPlan")&&!context.path("queryPlan").isObject())throw new IllegalArgumentException("无效的上一轮计划");
        if(context.has("historyComplete")&&!context.path("historyComplete").isBoolean())throw new IllegalArgumentException("无效的历史完整性标志");
        validateRound(context,false);
        if(context.has("candidateHistory")) {
            JsonNode history=context.path("candidateHistory");
            if(!history.isArray()||history.size()>3)throw new IllegalArgumentException("历史候选轮数无效");
            for(JsonNode round:history) {
                if(!round.isObject())throw new IllegalArgumentException("无效的历史候选轮次");
                round.fieldNames().forEachRemaining(field->{if(!Set.of("candidateIds","candidates","expiresAt").contains(field))throw new IllegalArgumentException("历史候选轮次包含未知字段");});
                validateRound(round,true);
            }
        }
    }
    private static void validateRound(JsonNode round,boolean history) {
        for(String field:List.of("candidateIds","candidates"))
            if(round.has(field)&&(!round.path(field).isArray()||round.path(field).size()>30)||history&&!round.has(field))throw new IllegalArgumentException("上下文候选数量无效");
        for(JsonNode id:round.path("candidateIds"))if(!id.isTextual()||!id.asText().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("上下文候选 ID 无效");
        for(JsonNode candidate:round.path("candidates"))if(!candidate.isObject()||candidate.toString().length()>500)throw new IllegalArgumentException("上下文候选格式无效");
        if(round.has("expiresAt")||history) {
            try { if(!round.path("expiresAt").isTextual())throw new IllegalArgumentException();Instant.parse(round.path("expiresAt").asText()); }
            catch(RuntimeException e) { throw new IllegalArgumentException("上下文有效期无效"); }
        }
        if(history) {
            List<String> ids=new ArrayList<>();round.path("candidateIds").forEach(id->ids.add(id.asText()));
            if(new HashSet<>(ids).size()!=ids.size()||round.path("candidates").size()!=ids.size())throw new IllegalArgumentException("历史候选身份不一致");
            for(int i=0;i<ids.size();i++)if(!round.path("candidates").get(i).path("id").asText().equals(ids.get(i)))throw new IllegalArgumentException("历史候选身份不一致");
        }
    }
    static boolean referencesFirst(String text) { return FIRST.matcher(text).find(); }
    private static boolean live(JsonNode round,boolean requireExpiry) {
        if(!round.isObject())return false;
        if(!round.has("expiresAt"))return !requireExpiry;
        try { return Instant.now().isBefore(Instant.parse(round.path("expiresAt").asText())); }
        catch(RuntimeException e) { return false; }
    }
    static Set<String> validMovieIds(JsonNode request) {
        JsonNode context=request.path("context");Set<String> ids=new HashSet<>();
        if(live(context,false))context.path("candidateIds").forEach(id->{if(id.isTextual())ids.add(id.asText());});
        // Historical identities are used only for explicit older-list detail questions.
        if(referencesFirst(ExplicitQueryConstraints.constraintText(request.path("message").asText()))&&context.path("candidateHistory").isArray()&&context.path("candidateHistory").size()<=3)
            for(JsonNode round:context.path("candidateHistory"))if(live(round,true))round.path("candidateIds").forEach(id->{if(id.isTextual())ids.add(id.asText());});
        return ids;
    }
    static void ground(JsonNode request,ObjectNode result) {
        String message=request.path("message").asText(),text=ExplicitQueryConstraints.constraintText(message);
        if(!referencesFirst(text))return;
        result.remove(List.of("movieId","ordinal"));
        JsonNode context=request.path("context"),history=context.path("candidateHistory");
        if(!live(context,false)||!context.path("historyComplete").asBoolean()||!history.isArray()||history.size()>3) { clarify(result,OLDER_CLARIFICATION);return; }
        for(JsonNode round:history)if(!live(round,true)) { clarify(result,OLDER_CLARIFICATION);return; }
        JsonNode first=history.isEmpty()?context:history.get(0);
        List<String> ids=new ArrayList<>();first.path("candidateIds").forEach(id->{if(id.isTextual())ids.add(id.asText());});
        if(ids.isEmpty()||ids.size()>30) { clarify(result,"更早列表没有可核对的影片候选，请补充片名及版本信息。");return; }
        String selected="";var ordinal=ORDINAL.matcher(text);
        if(ordinal.find()) {
            ExplicitQueryConstraints.putOrdinal(ordinal.group(1),result);
            if(!result.has("ordinal"))return;
            int index=result.path("ordinal").asInt()-1;result.remove("ordinal");
            if(index>=ids.size()) { clarify(result,"更早列表没有这个序号，请提供那部的片名或有效序号。");return; }
            selected=ids.get(index);
        }else if(text.matches("(?s).*(?:首部|首片).*"))selected=ids.get(0);
        var suppliedId=ID.matcher(message);
        if(suppliedId.find()) {
            String id=suppliedId.group(1);
            if(!ids.contains(id)||!selected.isBlank()&&!selected.equals(id)) { clarify(result,"提供的影片 ID 与更早列表的候选或序号不一致，请核对片名及 ID。");return; }
            selected=id;
        }
        ObjectNode literal=result.deepCopy();literal.removeAll();ExplicitQueryConstraints.ground(text,literal,true);
        var titles=Pattern.compile("《([^》]{1,120})》").matcher(message);List<String> quoted=new ArrayList<>();while(titles.find())quoted.add(titles.group(1));
        if(!quoted.isEmpty()) {
            Set<String> matched=new LinkedHashSet<>();
            for(JsonNode candidate:first.path("candidates"))if(ids.contains(candidate.path("id").asText())&&quoted.stream().anyMatch(title->sameTitle(candidate,title))&&matchesYear(candidate,literal))matched.add(candidate.path("id").asText());
            if(matched.size()!=1||!selected.isBlank()&&!matched.contains(selected)) { clarify(result,"提供的片名与更早列表的候选或序号不一致，或存在同名版本，请核对片名、年份及 ID。");return; }
            selected=matched.iterator().next();
        }
        if(selected.isBlank()) { clarify(result,"请指出更早列表中的序号、片名或影片 ID，再核对本次详情。");return; }
        if(literal.has("yearFrom")||literal.has("yearTo")){
            JsonNode identity=null;for(JsonNode candidate:first.path("candidates"))if(candidate.path("id").asText().equals(selected))identity=candidate;
            if(identity==null||!matchesYear(identity,literal)){clarify(result,"本轮指定年份与更早列表影片的版本不一致或资料不足，请核对片名、年份及 ID。");return;}
        }
        String clarification=result.path("clarification").asText().replace(OLDER_CLARIFICATION,"").trim();
        if(!clarification.isBlank()) { result.put("clarification",clarification);return; }
        clearUnmentionedFilters(text,result);
        result.remove(List.of("clarification","semanticText","seedTitle","replaceRanges","titles"));
        result.put("intent","details").put("inheritContext",false).put("scope","catalog").put("movieId",selected);
    }
    private static void clearUnmentionedFilters(String text,ObjectNode result) {
        // A fixed historical identity must not carry conditions proposed from the latest result set.
        ObjectNode literal=result.deepCopy();literal.removeAll();ExplicitQueryConstraints.ground(text,literal,true);
        for(String field:List.of("yearFrom","yearTo","minRuntime","maxRuntime","minRating","maxRating","limit","sort")) {
            result.remove(field);if(literal.has(field))result.set(field,literal.path(field).deepCopy());
        }
        for(String field:List.of("actors","actorsAny","directors","directorsAny","excludedActors","excludedDirectors","genresAny","genresAll","excludedGenres","regionsAny","languagesAny","excludedLanguages","excludedIds")) {
            var values=result.arrayNode();
            for(JsonNode value:result.path(field))if(value.isTextual()&&!key(value.asText()).isBlank()&&key(text).contains(key(value.asText())))values.add(value.deepCopy());
            result.remove(field);if(!values.isEmpty())result.set(field,values);
        }
        String category=result.path("category").asText();
        if(!(category.equals("mv")&&text.matches("(?s).*(?:电影|影片|片子).*"))&&!(category.equals("tv")&&text.matches("(?s).*(?:电视剧|剧集).*"))&&!(category.equals("ac")&&text.contains("动漫")))result.remove("category");
        String source=result.path("ratingSource").asText();
        if(!Set.of("douban","imdb","tmdb").contains(source)||!text.contains(switch(source){case "douban"->"豆瓣";case "imdb"->"imdb";default->"tmdb";})&&!text.contains(source))result.remove("ratingSource");
    }
    private static boolean matchesYear(JsonNode candidate,JsonNode literal){
        if(!literal.has("yearFrom")&&!literal.has("yearTo"))return true;
        if(!candidate.path("year").isIntegralNumber())return false;
        int year=candidate.path("year").asInt();return year>=literal.path("yearFrom").asInt(0)&&year<=literal.path("yearTo").asInt(9999);
    }
    private static boolean sameTitle(JsonNode candidate,String title) {
        return List.of("titleCn","titleEn").stream().anyMatch(field->!candidate.path(field).asText().isBlank()&&key(candidate.path(field).asText()).equals(key(title)));
    }
    private static String key(String value) { return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]",""); }
    private static void clarify(ObjectNode result,String message) {
        result.remove(List.of("movieId","ordinal"));result.put("intent","clarify").put("inheritContext",false);
        String previous=result.path("clarification").asText().replace(OLDER_CLARIFICATION,"").trim();
        String combined=previous.isBlank()?message:previous+" "+message;
        result.put("clarification",combined.substring(0,Math.min(300,combined.length())));
    }
}
