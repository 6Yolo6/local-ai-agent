package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Recognizes explicit public metadata requests outside quoted titles. */
final class ExplicitMetadataQuery {
    private ExplicitMetadataQuery() {}

    static boolean announcement(String text) {
        boolean event=text.matches("(?s).*(?:公告|官方.*(?:宣布|公布|发布)|重映|上映安排).*" );
        boolean check=text.matches("(?s).*(?:来源|出处|发布日期|日期|截至|有没有|是否|哪些|最新|查|查询|检索).*" );
        boolean refused=text.matches("(?s).*(?:不要|不用|不必|别|禁止)\\s*(?:查|查询|找)(?:官方)?(?:公告|上映|重映).*" );
        return event&&check&&!refused;
    }

    static boolean versionList(String text) {
        boolean enumeration=text.contains("版本")&&text.matches("(?s).*(?:多个|不同|各个|几个|所有|哪些|各版|区分|列出).*" );
        boolean refused=text.matches("(?s).*(?:不要|不用|不必|不需要|别)(?:列出|列|展示|区分|查)(?:其他|各个|多个|所有|不同)?版本.*" );
        return enumeration&&!refused;
    }

    static boolean directors(String text) {return requested(text,"导演");}
    static boolean actors(String text) {return requested(text,"主演")||requested(text,"演员");}
    static boolean years(String text) {
        return versionList(text)||text.matches("(?s).*(?:列出|列|写清|写出|标|展示|显示|注明|给出)[^，。；,;]{0,8}年份.*" );
    }
    static boolean originalLanguage(String text) {
        return text.matches("(?s).*(?:原始对白|原始语言|原版对白|原声语言|发行配音|配音语言).*" );
    }
    private static boolean requested(String text,String field) {
        return text.contains(field)&&!text.matches("(?s).*(?:不要|不用|不必|不需要|别)(?:列出|列|写|提供)?"+field+".*");
    }

    static void ground(JsonNode request,ObjectNode result,boolean followup,String text) {
        boolean subject=!result.path("titles").isEmpty()||followup&&!request.path("context").path("queryPlan").path("titles").isEmpty();
        if(announcement(text)) {
            result.put("intent","web");
            result.remove(List.of("clarification","semanticText","ordinal","movieId","regionsAny"));
            unverified(result,"官方发行公告的地区、版本及发布日期需要实际官方来源核验");
        }else if(subject&&versionList(text)) {
            metadataIntent(result,"search");
            result.remove(List.of("semanticText","ordinal","movieId"));
        }else if(subject&&(directors(text)||actors(text))&&text.matches("(?s).*(?:(?:主演|演员|导演).*(?:谁|有哪些|名单|资料)|(?:查|查询|看看|给|列).*(?:主演|演员|导演)).*" )) {
            metadataIntent(result,"details");
            result.remove("semanticText");
        }
        if(originalLanguage(text))
            unverified(result,"片库语言字段未区分原始对白与发行配音，原始语言条件仍待核验");
        if(contentRestriction(text))
            unverified(result,"片库普通简介不能逐场核验镜头内容和伤害排除条件，需要完整内容指南核验");
    }

    private static boolean contentRestriction(String text) {
        for(String clause:text.split("[，。；,;!?！？]"))
            if(clause.matches("(?s).*(?:没有|不能有|不得有|不含|排除|避开|不出现|不要|必须无|禁止|不允许).*(?:镜头|画面|场景|伤害|受伤|虐待|流血|裸露|死亡|自残|性暴力).*" ))return true;
        return false;
    }

    private static void metadataIntent(ObjectNode result,String intent) {
        if(!List.of("web","similar").contains(result.path("intent").asText()))result.put("intent",intent);
        result.remove("clarification");
    }
    private static void unverified(ObjectNode result,String condition) {
        var values=result.withArray("unsupportedConditions");
        for(JsonNode item:values)if(item.asText().equals(condition))return;
        if(values.size()<10)values.add(condition);
    }
}
