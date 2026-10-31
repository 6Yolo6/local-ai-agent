package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/** Literal ranges and list operations; quoted titles are never filtering instructions. */
final class ExplicitQueryConstraints {
    private ExplicitQueryConstraints() {}
    private static final String COUNT="(?:[-+]?[0-9]+|[零一二两三四五六七八九十百]{1,6})";
    private static final String AMOUNT="(?:[-+]?[0-9]+(?:\\.[0-9]+)?|[零一二两三四五六七八九十百]{1,6})";
    private static final String NUMBER="([-+]?[0-9]+(?:\\.[0-9]+)?)(?![0-9.])";
    private static final String YEAR="(?<![0-9])([-+]?[0-9]+)(?![0-9])";
    private static final Pattern YEAR_RANGE=Pattern.compile(YEAR+"年?\\s*(?:到|至|[-–])\\s*"+YEAR+"年?");
    private static final Pattern YEAR_BOUND=Pattern.compile(YEAR+"年?\\s*(及以后|及之后|及以前|及之前|以后|之后|以前|之前|以来|起|后|前)");
    private static final Pattern RECENT=Pattern.compile("(?:近|最近)("+COUNT+")年");
    private static final Pattern EXACT_YEAR=Pattern.compile(YEAR+"年(?:(?:那个|这个|该|的)?版本|版|上映|的(?:电影|影片|剧集))");
    private static final Pattern SCORE_RANGE=Pattern.compile("(?:豆瓣|douban|imdb|tmdb|评分|分数)[^，。；,;]{0,5}?"+NUMBER+"\\s*(?:到|至|[-–])\\s*"+NUMBER+"\\s*分?");
    private static final Pattern SCORE=Pattern.compile("(?:豆瓣|douban|imdb|tmdb|评分|分数)[^，。；,;]{0,5}?(至少|不低于|高于|大于|最多|不高于|低于|小于)?\\s*"+NUMBER+"\\s*分?(以上|以下)?");
    private static final Pattern RUNTIME_RANGE=Pattern.compile("(?<![0-9.])("+AMOUNT+")\\s*(?:到|至|[-–])\\s*("+AMOUNT+")\\s*(分钟|小时|钟头)");
    private static final Pattern RUNTIME=Pattern.compile("(?<![0-9.])(不能超过|不超过|最多|低于|少于|小于|不多于|至少|不少于|超过|大于)?\\s*("+AMOUNT+")\\s*(分钟|小时|钟头)(以内|以下|以上|以外)?");
    private static final Pattern LIMIT=Pattern.compile("(?:最多|至多|不超过|不多于|列出?|给我?|要|展示|推荐|先给我?)\\s*("+COUNT+")\\s*部");
    private static final Pattern TOP=Pattern.compile("前("+COUNT+")(?:部|名)?(?=[，。；,;!?！？]|$)");

    static String constraintText(String message) {
        return Normalizer.normalize(message,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("《[^》]*》"," ");
    }
    static boolean isFollowup(String text) {
        return text.matches("(?s).*(?:刚才|其中|前面|上面|这部|这些|那份|最开始|第一轮|最早那份|[0-9]+年(?:那个|这个|该|的)?版本|那个版本|该版本|第[一二两三四五六七八九十0-9]+部|只留|再筛|换一批|其他条件不变|其余不变|条件.*别改|改成|改为|改用|换成|换用|重新给|重新找|再按).*")
            ||text.matches("(?s)^\\s*(?:按|再按).*(?:排序|排|从高到低|从低到高).*")
            ||text.matches("(?s)^\\s*(?:不要|排除|别放)[^。!?！？]*(?:的|作品|影片|电影)?[。!?！？]?$");
    }
    static boolean previousResults(String text) {
        return text.matches("(?s).*(?:其中|结果只留|刚才的结果|刚才结果|只留|再筛|这些|那份|最开始).*" )
            ||text.matches("(?s)^\\s*(?:按|再按).*(?:排序|排|从高到低|从低到高).*");
    }
    static void ground(String text,ObjectNode result,boolean followup) {
        // Model-proposed numbers and reset commands need literal evidence in this turn.
        result.remove(List.of("yearFrom","yearTo","minRuntime","maxRuntime","minRating","maxRating","ratingSource","replaceRanges","sort"));
        years(text,result);
        scores(text,result);
        runtimes(text,result);
        var wanted=LIMIT.matcher(text);
        var top=TOP.matcher(text);
        if(wanted.find())putLimit(result,countValue(wanted.group(1)));
        else if(top.find())putLimit(result,countValue(top.group(1)));
        else if(followup)result.remove("limit");
        else result.put("limit",10);
        if(result.path("limit").asInt(1)<1){result.remove("limit");clarify(result,"请给出至少一部的查询数量。");}
        if(text.contains("从旧到新")||text.contains("从早到晚")||text.contains("从早到新"))result.put("sort","year_asc");
        else if(text.contains("从新到旧")||text.contains("从晚到早"))result.put("sort","year_desc");
        else if(text.matches("(?s).*(?:豆瓣|douban|imdb|tmdb|评分|分数).*(?:从低到高|升序).*"))clarify(result,"当前支持评分从高到低排序，请确认是否按评分降序排列。");
        else if(text.matches("(?s).*(?:按|依|根据).*(?:豆瓣|douban|imdb|tmdb|评分|分数).*(?:排|排序).*" )
            ||text.matches("(?s).*(?:评分|分数).*(?:从高到低|降序|最高).*"))result.put("sort","rating_desc");
        if(followup) {
            addReplacement(text,result,"year","年份|上映年份|年代|年份范围","yearFrom","yearTo");
            addReplacement(text,result,"runtime","片长|时长|长度|片长范围|时长范围","minRuntime","maxRuntime");
            addReplacement(text,result,"rating","评分|分数|评分范围","minRating","maxRating");
            if(result.has("replaceRanges")&&!previousResults(text))result.put("scope","catalog");
        }
        for(String group:List.of("year","runtime","rating")) {
            String lower=switch(group){case "year"->"yearFrom";case "runtime"->"minRuntime";default->"minRating";};
            String upper=switch(group){case "year"->"yearTo";case "runtime"->"maxRuntime";default->"maxRating";};
            BigDecimal min=BigDecimal.valueOf(group.equals("year")?1900:group.equals("runtime")?1:0);
            BigDecimal max=BigDecimal.valueOf(group.equals("year")?2100:group.equals("runtime")?600:10);
            if(outside(result,lower,min,max)||outside(result,upper,min,max)) {
                result.remove(List.of(lower,upper));
                removeReplacement(result,group);
                clarify(result,switch(group){case "year"->"当前片库可核对的上映年份范围为1900至2100年";case "runtime"->"当前片库可核对的片长范围为1至600分钟";default->"当前片库评分范围为0至10分";}+"，请确认是否调整这项条件。");
            }
            if(result.has(lower)&&result.has(upper)&&result.path(lower).decimalValue().compareTo(result.path(upper).decimalValue())>0) {
                result.remove(List.of(lower,upper));
                removeReplacement(result,group);
                clarify(result,switch(group){case "year"->"上映年份范围的起点晚于终点";case "runtime"->"片长的最低要求超过最高限制";default->"评分的最低要求超过最高限制";}+"，两个硬条件不能同时满足。请确认要修改哪一项。");
            }
            if(!group.equals("rating"))for(String endpoint:List.of(lower,upper))if(result.has(endpoint))result.put(endpoint,result.path(endpoint).decimalValue().intValueExact());
        }
        if(text.matches("(?s).*(?:最开始|第一轮|最早那份).*")) {
            // The bridge context contains the latest result set only, not a verified older snapshot.
            result.remove(List.of("movieId","ordinal"));
            clarify(result,"当前可核对的候选上下文只有最新一轮，无法确定您指的更早列表中的影片。请提供那部的片名或那份列表，再核对对应资料。");
        }
    }
    static void putOrdinal(String value,ObjectNode result) {
        BigDecimal ordinal=countValue(value);
        result.remove("ordinal");
        if(ordinal.compareTo(BigDecimal.ONE)<0||ordinal.compareTo(BigDecimal.valueOf(30))>0)clarify(result,"当前列表序号支持第1至30部，请提供有效序号或片名。");
        else result.put("ordinal",ordinal.intValueExact());
    }
    private static boolean outside(ObjectNode result,String field,BigDecimal min,BigDecimal max) {
        if(!result.has(field))return false;
        BigDecimal value=result.path(field).decimalValue();
        return value.compareTo(min)<0||value.compareTo(max)>0;
    }
    private static void putLimit(ObjectNode result,BigDecimal count) {
        result.remove("limit");
        if(count.compareTo(BigDecimal.ONE)<0||count.compareTo(BigDecimal.valueOf(20))>0)clarify(result,"当前每次查询数量支持1至20部，请确认是否调整数量。");
        else result.put("limit",count.intValueExact());
    }
    private static void removeReplacement(ObjectNode result,String group) {
        if(!result.has("replaceRanges"))return;
        var replacements=result.withArray("replaceRanges");
        for(int i=replacements.size()-1;i>=0;i--)if(replacements.get(i).asText().equals(group))replacements.remove(i);
        if(replacements.isEmpty())result.remove("replaceRanges");
    }
    private static void years(String text,ObjectNode result) {
        var range=YEAR_RANGE.matcher(text);
        var bound=YEAR_BOUND.matcher(text);
        var recent=RECENT.matcher(text);
        var exact=EXACT_YEAR.matcher(text);
        boolean rangeFound=false;
        while(range.find())if(range.group().contains("年")||text.substring(Math.max(0,range.start()-10),range.start()).matches("(?s).*(?:年份|上映年份|年代)(?:范围)?[：:是为\\s]*$")||range.group(1).replaceAll("[-+]","").length()>=4&&range.group(2).replaceAll("[-+]","").length()>=4){rangeFound=true;break;}
        boolean boundFound=false;
        while(bound.find())if(bound.group().contains("年")||bound.group(1).replaceAll("[-+]","").length()>=4){boundFound=true;break;}
        if(rangeFound)result.put("yearFrom",new BigDecimal(range.group(1))).put("yearTo",new BigDecimal(range.group(2)));
        else if(boundFound) {
            BigDecimal year=new BigDecimal(bound.group(1));String direction=bound.group(2);
            boolean before=Set.of("以前","之前","及以前","及之前","前").contains(direction);
            if(Set.of("后","之后").contains(direction))year=year.add(BigDecimal.ONE);
            if(Set.of("前","之前").contains(direction))year=year.subtract(BigDecimal.ONE);
            result.put(before?"yearTo":"yearFrom",year);
        }else if(recent.find()) {
            int year=LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear();
            BigDecimal count=countValue(recent.group(1));
            if(count.compareTo(BigDecimal.ONE)<0)clarify(result,"近几年需要至少一年的有效时间范围，请确认年份条件。");
            else result.put("yearFrom",BigDecimal.valueOf(year+1).subtract(count)).put("yearTo",year);
        }else if(exact.find())result.put("yearFrom",new BigDecimal(exact.group(1))).put("yearTo",new BigDecimal(exact.group(1)));
    }
    private static void scores(String text,ObjectNode result) {
        var range=SCORE_RANGE.matcher(text);
        if(range.find())result.put("minRating",new BigDecimal(range.group(1))).put("maxRating",new BigDecimal(range.group(2)));
        else {
            var score=SCORE.matcher(text);
            while(score.find()) {
                String direction=Objects.toString(score.group(1),"");BigDecimal n=new BigDecimal(score.group(2));
                if(n.compareTo(BigDecimal.ZERO)>=0&&n.compareTo(BigDecimal.TEN)<=0) {
                    if(Set.of("高于","大于").contains(direction))n=BigDecimal.valueOf(Math.nextUp(n.doubleValue()));
                    if(Set.of("低于","小于").contains(direction))n=BigDecimal.valueOf(Math.nextDown(n.doubleValue()));
                }
                result.put(Set.of("最多","不高于","低于","小于").contains(direction)||"以下".equals(score.group(3))?"maxRating":"minRating",n);
            }
        }
    }
    private static void runtimes(String text,ObjectNode result) {
        var range=RUNTIME_RANGE.matcher(text);
        if(range.find()) {
            result.put("minRuntime",minutes(range.group(1),range.group(3),true,false));
            result.put("maxRuntime",minutes(range.group(2),range.group(3),false,false));
        }else {
            var runtime=RUNTIME.matcher(text);
            while(runtime.find()) {
                String direction=Objects.toString(runtime.group(1),"");
                boolean lower=Set.of("至少","不少于","超过","大于").contains(direction)||"以上".equals(runtime.group(4));
                boolean strict=Set.of("超过","大于","低于","少于","小于").contains(direction);
                BigDecimal amount=minutes(runtime.group(2),runtime.group(3),lower,strict);
                result.put(lower?"minRuntime":"maxRuntime",amount);
            }
        }
    }
    private static BigDecimal minutes(String amount,String unit,boolean lower,boolean strict) {
        BigDecimal n=amount.matches("[-+]?[0-9]+(?:\\.[0-9]+)?")?new BigDecimal(amount):countValue(amount);
        if(!unit.equals("分钟"))n=n.multiply(BigDecimal.valueOf(60));
        // Catalog durations are integral minutes. Translate open boundaries without admitting equality.
        if(strict)return n.setScale(0,lower?RoundingMode.FLOOR:RoundingMode.CEILING).add(BigDecimal.valueOf(lower?1:-1));
        return n.setScale(0,lower?RoundingMode.CEILING:RoundingMode.FLOOR);
    }
    private static BigDecimal countValue(String value) {
        if(value.matches("[-+]?[0-9]+"))return new BigDecimal(value);
        String numbers="零一二三四五六七八九";int sum=0,current=0;
        for(char c:value.toCharArray()) {
            if(c=='十'||c=='百'){sum+=(current==0?1:current)*(c=='十'?10:100);current=0;}
            else current=c=='两'?2:numbers.indexOf(c);
        }
        return BigDecimal.valueOf(sum+current);
    }
    private static void addReplacement(String text,ObjectNode result,String group,String names,String lower,String upper) {
        if((result.has(lower)||result.has(upper))&&text.matches("(?s).*(?:"+names+")(?:范围)?\\s*(?:改成|改为|换成|替换为).*"))result.withArray("replaceRanges").add(group);
    }
    private static void clarify(ObjectNode result,String message) {
        result.put("intent","clarify");String previous=result.path("clarification").asText();
        String value=previous.isBlank()?message:previous+" "+message;
        result.put("clarification",value.substring(0,Math.min(300,value.length())));
    }
}
