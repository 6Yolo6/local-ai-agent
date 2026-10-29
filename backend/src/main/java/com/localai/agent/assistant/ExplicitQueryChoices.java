package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Grounds single-choice fields in explicit mentions, without treating exclusions as choices. */
final class ExplicitQueryChoices {
    private ExplicitQueryChoices() {}
    private static final Pattern CATEGORIES=Pattern.compile("电影|影片|片子|电视剧|剧集|动漫");
    private static final Pattern SOURCES=Pattern.compile("豆瓣|(?<![a-z])(?:douban|imdb|tmdb)(?![a-z])");
    private static final Pattern CLAUSES=Pattern.compile("[，。；,;!?！？\\r\\n]|(?:但是|而是|不过|但)");
    private static final Pattern NEGATIVE_BEFORE=Pattern.compile(
        "(?:不要|不用|不看|不是|排除|别放|别要|不含|不包括|除去|不喜欢|不能|不得|别|禁止|不使用|不采用|非)"
        +"(?:再|只|仅|用|拿|按|以|看|查|找|采用|使用|选|选用|当作|当成|任何|所有)*$");
    private static final Pattern NOT_EXCLUSIVE_BEFORE=Pattern.compile(
        "(?:(?:不要|不能|不得|别|不应|不该)(?:只|仅|光)(?:看|查|找|用|按|以|采用|使用)*|不只是|不仅是|不光是|不止是|不止|不只|不仅|不光)$");
    private static final Pattern POSITIVE_BEFORE=Pattern.compile(
        "(?:只要|只查|只看|改用|改成|换用|换成|要|查|找|看|用|按|以|采用|使用|选用|保留|包括|仍用|还是)$");
    private static final Pattern NEGATIVE_AFTER=Pattern.compile(
        "^(?:的)?(?:评分|分)?(?:都|也|又|均)?(?:不要|不用|不看|排除|除外|不能用|不能补|不能替代|别用|不采用|不使用)"
        +"(?:来)?(?:补|替代|代替|补齐)?(?:评分|分)?(?:了)?$");
    private static final Pattern CONNECTOR=Pattern.compile("(?:的)?(?:评分|分)?(?:和|或|与|以及|及|、|或者|都|也)*");
    private record Mention(String choice,int start,int end) {}
    private record Choices(Set<String> positive,Set<String> negative,boolean ambiguous) {}

    static void ground(String message,ObjectNode result) {
        // A title containing a category or provider name supplies no such filter.
        String normalized=Normalizer.normalize(message,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String text=normalized.replaceAll("《[^》]*》"," ");
        Choices categories=mentions(text,CATEGORIES);
        if(categories.positive.isEmpty()&&categories.negative.isEmpty()&&CATEGORIES.matcher(normalized).find())result.remove("category");
        apply("category",categories,result,"请确认这次要查询电影、剧集还是动漫；当前类别条件不能合并为一个明确选择。");
        apply("ratingSource",mentions(text,SOURCES),result,"请确认这次使用豆瓣、IMDb还是TMDB评分；当前评分来源不明确，不能混用或替换。");
    }

    private static void apply(String field,Choices choices,ObjectNode result,String clarification) {
        if(choices.positive.isEmpty()&&choices.negative.isEmpty())return;
        result.remove(field);
        if(!choices.ambiguous&&choices.positive.size()==1&&Collections.disjoint(choices.positive,choices.negative)) {
            result.put(field,choices.positive.iterator().next());
            return;
        }
        // An exclusion alone cannot be represented by these single-choice fields.
        // Clarify instead of falling back to an unrestricted category or "any" rating.
        result.put("intent","clarify");
        String previous=result.path("clarification").asText();
        String combined=previous.isBlank()?clarification:previous+" "+clarification;
        result.put("clarification",combined.substring(0,Math.min(300,combined.length())));
    }

    private static Choices mentions(String text,Pattern terms) {
        Set<String> positive=new LinkedHashSet<>(),negative=new LinkedHashSet<>();boolean ambiguous=false;
        for(String clause:CLAUSES.split(text)) {
            List<Mention> found=new ArrayList<>();var matcher=terms.matcher(clause);
            while(matcher.find())found.add(new Mention(canonical(matcher.group()),matcher.start(),matcher.end()));
            int[] polarity=new int[found.size()];
            for(int i=0;i<found.size();i++) {
                Mention mention=found.get(i);
                String before=compact(clause.substring(i==0?0:found.get(i-1).end,mention.start));
                String after=compact(clause.substring(mention.end));
                if(NOT_EXCLUSIVE_BEFORE.matcher(before).find()){polarity[i]=1;ambiguous=true;}
                else if(NEGATIVE_BEFORE.matcher(before).find()
                        ||i==found.size()-1&&NEGATIVE_AFTER.matcher(after).matches())polarity[i]=-1;
                else if(POSITIVE_BEFORE.matcher(before).find())polarity[i]=1;
            }
            // Carry polarity through a bare enumeration, in either direction:
            // "不要电影和剧集" and "电影和剧集都不要".
            for(int i=1;i<found.size();i++)if(polarity[i]==0&&polarity[i-1]<0&&joined(clause,found.get(i-1),found.get(i)))polarity[i]=-1;
            for(int i=found.size()-2;i>=0;i--)if(polarity[i]==0&&polarity[i+1]<0&&joined(clause,found.get(i),found.get(i+1)))polarity[i]=-1;
            for(int i=0;i<found.size();i++)(polarity[i]<0?negative:positive).add(found.get(i).choice);
        }
        return new Choices(positive,negative,ambiguous);
    }

    private static boolean joined(String clause,Mention left,Mention right) {
        return CONNECTOR.matcher(clause.substring(left.end,right.start).replaceAll("\\s","")).matches();
    }
    private static String compact(String text) {return text.replaceAll("[\\p{P}\\s]","");}
    private static String canonical(String value) {
        return switch(value) {
            case "电影","影片","片子" -> "mv";
            case "电视剧","剧集" -> "tv";
            case "动漫" -> "ac";
            case "豆瓣","douban" -> "douban";
            default -> value;
        };
    }
}
