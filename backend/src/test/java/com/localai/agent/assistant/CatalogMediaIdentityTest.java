package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogMediaIdentityTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode movie(String category,String summary){return mapper.createObjectNode().put("category",category).put("summary",summary);}
    @Test void broadCategoryDoesNotProveMovieIdentityOrOverrideExplicitTelevisionEvidence(){
        assertThat(CatalogMediaIdentity.describe(movie("mv","主人公踏上旅程。"),mapper).path("status").asText()).isEqualTo("uncertain");
        assertThat(CatalogMediaIdentity.describe(movie("mv","电视特辑节目，歌手演唱她的作品。"),mapper).path("status").asText()).isEqualTo("conflict");
        assertThat(CatalogMediaIdentity.describe(movie("tv","电视剧讲述一家人的生活。"),mapper).path("status").asText()).isEqualTo("contradicted");
    }
    @Test void actualMovieSourceSupportsOrdinaryAndAnimatedFilmsWhileRecordingsRemainUncertain(){
        for(String category:java.util.List.of("mv","ac")){
            ObjectNode plain=movie(category,"主人公踏上旅程。").put("tmdbType","movie");assertThat(CatalogMediaIdentity.describe(plain,mapper).path("status").asText()).isEqualTo("supported");
        }
        assertThat(CatalogMediaIdentity.describe(movie("mv","录音棚现场录像，乐队演奏最新专辑。").put("tmdbType","movie"),mapper).path("status").asText()).isEqualTo("uncertain");
        assertThat(CatalogMediaIdentity.describe(movie("mv","本片记录钢琴家的最后一場演奏。"),mapper).path("status").asText()).isEqualTo("supported");
    }
    @Test void forgedSuppliedSupportedStatusCannotOverrideObservedTelevisionConflict(){
        ObjectNode doc=movie("mv","电视特辑节目，歌手演唱她的作品。");ObjectNode supplied=doc.putObject("mediaIdentity").put("status","supported").put("kind","movie");supplied.putArray("evidence").addObject().put("source","summary").put("quote","歌手演唱她的作品");
        assertThat(CatalogMediaIdentity.describe(doc,mapper).path("status").asText()).isEqualTo("conflict");
    }
    @Test void suppliedActualButUnrelatedQuoteCannotPromoteAnUncertainCatalogRecord(){
        ObjectNode doc=movie("mv","主人公踏上旅程。");ObjectNode supplied=doc.putObject("mediaIdentity").put("status","supported").put("kind","movie");supplied.putArray("evidence").addObject().put("source","summary").put("quote","主人公踏上旅程");
        assertThat(CatalogMediaIdentity.describe(doc,mapper).path("status").asText()).isEqualTo("uncertain");
    }
    @Test void suppliedProofMustRemainBoundToTheActualSourceProjection(){
        ObjectNode doc=movie("mv","主人公踏上旅程。").put("tmdbType","movie");ObjectNode supplied=doc.putObject("mediaIdentity").put("status","supported").put("kind","movie");supplied.putArray("evidence").addObject().put("source","tmdbType").put("quote","movie");assertThat(CatalogMediaIdentity.describe(doc,mapper)).isEqualTo(supplied);
        supplied.withArray("evidence").get(0).deepCopy();((ObjectNode)supplied.withArray("evidence").get(0)).put("quote","forged");assertThat(CatalogMediaIdentity.describe(doc,mapper).path("evidence").get(0).path("quote").asText()).isEqualTo("movie");
    }

    @Test void namedTelevisionSpecialConflictCannotBeUpgradedByAnAvailableMovieSource(){
        ObjectNode doc=movie("mv","《午后旋律》是一部音乐电视特辑节目。").put("titleCn","午后旋律").put("tmdbType","movie");ObjectNode supplied=doc.putObject("mediaIdentity").put("status","conflict").put("kind","nonMovie");supplied.putArray("evidence").addObject().put("source","summary").put("quote","电视特辑节目");supplied.withArray("evidence").addObject().put("source","tmdbType").put("quote","movie");
        assertThat(CatalogMediaIdentity.describe(doc,mapper)).isEqualTo(supplied);doc.remove("mediaIdentity");assertThat(CatalogMediaIdentity.describe(doc,mapper).path("status").asText()).isEqualTo("conflict");
    }
    @Test void aTopicAboutTelevisionOrSomebodysFavouriteFilmDoesNotDescribeTheWorksIdentity(){
        assertThat(CatalogMediaIdentity.describe(movie("mv","本片讲述制作电视节目时发生的趣事。"),mapper).path("status").asText()).isEqualTo("supported");
        assertThat(CatalogMediaIdentity.describe(movie("mv","主人公最爱的电影是童年的一部喜剧。"),mapper).path("status").asText()).isEqualTo("uncertain");
    }
    @Test void leadingWhitespaceDoesNotShiftIdentityEvidenceAwayFromTheObservedCue(){
        for(String cue:java.util.List.of("电视特辑节目，歌手演唱她的作品。","本片记录钢琴家的最后一场演奏。","录音棚现场录像，乐队演奏最新专辑。")){
            String summary=" ".repeat(170)+cue;ObjectNode identity=CatalogMediaIdentity.describe(movie("mv",summary),mapper);
            String quote=identity.path("evidence").get(0).path("quote").asText();assertThat(summary).contains(quote);assertThat(quote).contains(cue.substring(0,6));assertThat(quote.length()).isLessThanOrEqualTo(160);
        }
    }
    @Test void legacySeasonDoesNotOverrideActualMovieEvidenceOrAloneProveTelevision(){
        assertThat(CatalogMediaIdentity.describe(movie("mv","主人公踏上旅程。").put("season",1).put("tmdbType","movie"),mapper).path("status").asText()).isEqualTo("supported");
        assertThat(CatalogMediaIdentity.describe(movie("mv","本片记录钢琴家的最后一场演奏。").put("season",2),mapper).path("status").asText()).isEqualTo("supported");
        for(String category:java.util.List.of("mv","ac")){
            ObjectNode identity=CatalogMediaIdentity.describe(movie(category,"主人公踏上旅程。").put("season",1),mapper);assertThat(identity.path("status").asText()).isEqualTo("uncertain");assertThat(identity.path("kind").asText()).isEqualTo("unknown");assertThat(identity.path("evidence").toString()).contains("season","1");
        }
        assertThat(CatalogMediaIdentity.describe(movie("mv","主人公踏上旅程。").put("season",2).put("tmdbType","tv"),mapper).path("status").asText()).isEqualTo("conflict");
        ObjectNode stale=movie("mv","主人公踏上旅程。").put("season",1).put("tmdbType","movie");ObjectNode supplied=stale.putObject("mediaIdentity").put("status","conflict").put("kind","nonMovie");supplied.putArray("evidence").addObject().put("source","season").put("quote","1");assertThat(CatalogMediaIdentity.describe(stale,mapper).path("status").asText()).isEqualTo("supported");
    }
}
