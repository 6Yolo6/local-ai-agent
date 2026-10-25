package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.localai.agent.ollama.OllamaService;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class MovieQueryPlannerTest {
 final ObjectMapper m=new ObjectMapper();final MovieQueryPlanner planner=new MovieQueryPlanner(mock(OllamaService.class),m);
 ObjectNode extract(String text,String model)throws Exception{return planner.grounded(m.createObjectNode().put("message",text),(ObjectNode)m.readTree(model));}
 @Test void runtimeRangesHoursAndChineseUnitsKeepBothBounds()throws Exception{
  assertThat(extract("时长95到120分钟，列两部","{\"intent\":\"search\"}").path("minRuntime").asInt()).isEqualTo(95);
  var p=extract("至少90分钟，不超过两小时","{\"intent\":\"search\"}");assertThat(p.path("minRuntime").asInt()).isEqualTo(90);assertThat(p.path("maxRuntime").asInt()).isEqualTo(120);
 }
 @Test void sourceAndRangeAreBoundToRatingInsteadOfCount()throws Exception{
  var p=extract("豆瓣7.5到8.5分，列三部","{\"intent\":\"search\",\"minRuntime\":90}");assertThat(p.path("minRating").asDouble()).isEqualTo(7.5);assertThat(p.path("maxRating").asDouble()).isEqualTo(8.5);assertThat(p.path("ratingSource").asText()).isEqualTo("douban");assertThat(p.has("minRuntime")).isFalse();
  assertThat(extract("给我7部","{\"intent\":\"search\",\"minRating\":7,\"yearFrom\":2026}").has("minRating")).isFalse();
 }
 @Test void punctuationStopsNegationFromLeakingToNextCondition()throws Exception{
  var p=extract("不要甲乙丙导演的，演员丁戊己主演","{\"intent\":\"search\",\"directors\":[\"甲乙丙\"],\"actors\":[\"丁戊己\"]}");assertThat(p.path("directors").isEmpty()).isTrue();assertThat(p.path("excludedDirectors").get(0).asText()).isEqualTo("甲乙丙");assertThat(p.path("actors").get(0).asText()).isEqualTo("丁戊己");
 }
 @Test void followupDoesNotResetThePreviousLimit()throws Exception{
  var r=m.createObjectNode().put("message","其中只留90分钟以内的");r.putObject("context").putObject("queryPlan").put("limit",4);var p=planner.grounded(r,(ObjectNode)m.readTree("{\"intent\":\"search\",\"limit\":10}"));assertThat(p.path("inheritContext").asBoolean()).isTrue();assertThat(p.has("limit")).isFalse();assertThat(p.path("scope").asText()).isEqualTo("previousResults");
 }
 @Test void inventedIdsAndEntitiesFailBeforeExecution()throws Exception{
  assertThatThrownBy(()->extract("查演员张三","{\"intent\":\"search\",\"actors\":[\"李四\"]}")).isInstanceOf(OllamaService.OllamaException.class);
 }
 @Test void explicitWebTitleAndOrdinalOverrideMisclassification()throws Exception{
  var p=extract("联网查《样片》剧情","{\"intent\":\"search\",\"seedTitle\":\"样片\"}");assertThat(p.path("intent").asText()).isEqualTo("web");assertThat(p.path("titles").get(0).asText()).isEqualTo("样片");assertThat(p.has("seedTitle")).isFalse();
  assertThat(extract("第两部主演是谁","{\"intent\":\"search\"}").path("ordinal").asInt()).isEqualTo(2);
 }
}
