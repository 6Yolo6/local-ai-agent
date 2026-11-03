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
 @Test void semanticRetrievalOmitsRequestSuffixesAndDuplicateSimilarityTitles()throws Exception{
  var p=extract("类似《样片》的文化沟通题材电影","{\"intent\": \"similar\", \"seedTitle\": \"样片\", \"semanticText\": \"类似《样片》的文化沟通题材电影\"}");
  assertThat(p.path("semanticText").asText()).isEqualTo("文化沟通");assertThat(p.path("seedTitle").asText()).isEqualTo("样片");
  assertThat(extract("类似《雨》的雨天记忆题材电影","{\"intent\":\"similar\",\"seedTitle\":\"雨\",\"semanticText\":\"类似《雨》的雨天记忆题材电影\"}").path("semanticText").asText()).isEqualTo("雨天记忆");
  assertThat(extract("找讲相互信任的电影","{\"intent\": \"search\", \"semanticText\": \"相互信任的电影\"}").path("semanticText").asText()).isEqualTo("相互信任");
 }


 @Test void excludedCategoryNeverOverridesTheAffirmativeCategoryInEitherOrder()throws Exception{
  for(String text:new String[]{"找剧情电影，不要剧集","剧集不要，找剧情电影","不要剧集，只要电影","只要电影而不是电视剧"}) {
   var p=extract(text,"{\"intent\":\"search\",\"category\":\"tv\"}");
   assertThat(p.path("category").asText()).as(text).isEqualTo("mv");assertThat(p.path("intent").asText()).isEqualTo("search");
  }
  for(String text:new String[]{"不要电影，只查电视剧","找剧集，电影不要"})
   assertThat(extract(text,"{\"intent\":\"search\",\"category\":\"mv\"}").path("category").asText()).as(text).isEqualTo("tv");
 }
 @Test void categoryEnumerationRetainsNegationWithoutLeakingAcrossPositiveChoices()throws Exception{
  for(String text:new String[]{"不要电影和剧集，只看动漫","电影和剧集都不要，只看动漫","不要电影或电视剧，要动漫"})
   assertThat(extract(text,"{\"intent\":\"search\",\"category\":\"mv\"}").path("category").asText()).as(text).isEqualTo("ac");

 }
 @Test void ambiguousOrUnrepresentableCategoryRequestsClarifyInsteadOfGuessing()throws Exception{
  for(String text:new String[]{"找电影和剧集","不要剧集","要电影，但电影又不要"}) {
   var p=extract(text,"{\"intent\":\"search\",\"category\":\"tv\"}");
   assertThat(p.path("intent").asText()).as(text).isEqualTo("clarify");assertThat(p.has("category")).isFalse();assertThat(p.path("clarification").asText()).isNotBlank();
  }
 }
 @Test void quotedTitlesSupplyNeitherCategoryNorRatingSource()throws Exception{
  var p=extract("查《电视剧》这部电影，按IMDb评分排序","{\"intent\":\"search\",\"category\":\"tv\",\"ratingSource\":\"douban\"}");
  assertThat(p.path("category").asText()).isEqualTo("mv");assertThat(p.path("ratingSource").asText()).isEqualTo("imdb");assertThat(p.path("titles").get(0).asText()).isEqualTo("电视剧");
  p=extract("查《电影 IMDb 豆瓣》的剧情","{\"intent\":\"search\",\"category\":\"mv\",\"ratingSource\":\"imdb\"}");
  assertThat(p.has("category")).isFalse();assertThat(p.has("ratingSource")).isFalse();assertThat(p.path("intent").asText()).isEqualTo("search");
  assertThat(extract("查《电影》的剧集","{\"intent\":\"search\",\"category\":\"mv\"}").path("category").asText()).isEqualTo("tv");
 }
 @Test void excludedRatingSourceCannotReplaceTheRequestedSourceInEitherOrder()throws Exception{
  for(String text:new String[]{"找电影，按豆瓣评分排序，不能拿IMDb分补","IMDb分不用，按豆瓣评分排","按豆瓣评分，不要IMDb","不用IMDb而是用豆瓣","没有豆瓣分的放最后，不能用IMDb补"}) {
   var p=extract(text,"{\"intent\":\"search\",\"ratingSource\":\"imdb\"}");
   assertThat(p.path("ratingSource").asText()).as(text).isEqualTo("douban");assertThat(p.path("intent").asText()).isEqualTo("search");
  }
  assertThat(extract("别用豆瓣，按IMDb评分排","{\"intent\":\"search\",\"ratingSource\":\"douban\"}").path("ratingSource").asText()).isEqualTo("imdb");
 }
 @Test void multipleAffirmativeOrContradictorySourcesClarifyWithoutMixingScores()throws Exception{
  for(String text:new String[]{"按豆瓣和IMDb评分排序","按IMDb和豆瓣评分排序","按豆瓣评分，但豆瓣又不要","不要IMDb评分"}) {
   var p=extract(text,"{\"intent\":\"search\",\"ratingSource\":\"imdb\"}");
   assertThat(p.path("intent").asText()).as(text).isEqualTo("clarify");assertThat(p.has("ratingSource")).isFalse();assertThat(p.path("clarification").asText()).contains("评分");
  }
 }
 @Test void supportedSourceAliasesAndExistingNumericExamplesRemainCompatible()throws Exception{
  for(String text:new String[]{"找喜剧或冒险电影，2020年以后，IMDB至少7.5分，最多90分钟，列6部","查IMDb评分至少7.5分的电影"}) {
   var p=extract(text,"{\"intent\":\"search\",\"ratingSource\":\"douban\"}");
   assertThat(p.path("ratingSource").asText()).isEqualTo("imdb");assertThat(p.path("minRating").asDouble()).isEqualTo(7.5);assertThat(p.path("category").asText()).isEqualTo("mv");
  }
  assertThat(extract("按TmDb评分排","{\"intent\":\"search\"}").path("ratingSource").asText()).isEqualTo("tmdb");
  assertThat(extract("按Douban评分排","{\"intent\":\"search\"}").path("ratingSource").asText()).isEqualTo("douban");
  assertThat(extract("找评分至少7分的电影","{\"intent\":\"search\",\"ratingSource\":\"imdb\"}").has("ratingSource")).isFalse();
 }
 @Test void explicitSourceChangeInKnownFollowupKeepsExistingInheritanceContract()throws Exception{
  var r=m.createObjectNode().put("message","刚才的结果改用IMDb评分，别用豆瓣");r.putObject("context").putObject("queryPlan").put("ratingSource","douban");
  var p=planner.grounded(r,(ObjectNode)m.readTree("{\"intent\":\"search\",\"ratingSource\":\"douban\"}"));
  assertThat(p.path("inheritContext").asBoolean()).isTrue();assertThat(p.path("scope").asText()).isEqualTo("previousResults");assertThat(p.path("ratingSource").asText()).isEqualTo("imdb");
 }


 @Test void negatingExclusiveChoiceDoesNotExcludeTheMentionedOption()throws Exception{
  for(String text:new String[]{"不要只看电影，也要剧集","不能只看剧集，也要电影","不只是电影","不要只看电影"}) {
   var p=extract(text,"{\"intent\":\"search\",\"category\":\"tv\"}");
   assertThat(p.path("intent").asText()).as(text).isEqualTo("clarify");assertThat(p.has("category")).isFalse();
  }
  for(String text:new String[]{"不能只用豆瓣，也要IMDb","不要只按IMDb，还要豆瓣","不只是豆瓣","不能只用豆瓣"}) {
   var p=extract(text,"{\"intent\":\"search\",\"ratingSource\":\"imdb\"}");
   assertThat(p.path("intent").asText()).as(text).isEqualTo("clarify");assertThat(p.has("ratingSource")).isFalse();
  }
 }
 @Test void ratingNounsDoNotBreakGroupedSourceExclusions()throws Exception{
  for(String text:new String[]{"IMDb评分和豆瓣评分都不用，用TMDB评分","豆瓣分与IMDb分均不采用，按TMDB排","不用IMDb评分和豆瓣评分，采用TmDb分"}) {
   var p=extract(text,"{\"intent\":\"search\",\"ratingSource\":\"imdb\"}");
   assertThat(p.path("ratingSource").asText()).as(text).isEqualTo("tmdb");assertThat(p.path("intent").asText()).isEqualTo("search");
  }
 }
 @Test void ambiguousAnimeMovieWordingNeverSilentlyBroadensToOrdinaryMovies()throws Exception{
  var p=extract("找动漫电影，不要剧集","{\"intent\":\"search\",\"category\":\"ac\",\"genresAll\":[\"动画\"]}");
  assertThat(p.path("intent").asText()).isEqualTo("clarify");assertThat(p.has("category")).isFalse();
  // The ungrounded alias is not preserved as a claim that the full plan was verified.
  assertThat(p.path("genresAll").isEmpty()).isTrue();
  p=extract("找动画电影，不要剧集","{\"intent\":\"search\",\"category\":\"tv\"}");
  assertThat(p.path("intent").asText()).isEqualTo("search");assertThat(p.path("category").asText()).isEqualTo("mv");
  assertThat(p.path("genresAll").get(0).asText()).isEqualTo("动画");
 }


 @Test void categoryGuardDoesNotEraseExistingModelClassificationOutsideItsVocabulary()throws Exception{
  var p=extract("想看几部美剧","{\"intent\":\"search\",\"category\":\"tv\"}");
  assertThat(p.path("category").asText()).isEqualTo("tv");assertThat(p.path("intent").asText()).isEqualTo("search");
 }


 @Test void notOnlyCopulaDoesNotTurnIncludedCategoriesOrSourcesIntoExclusions()throws Exception{
  for(String text:new String[]{"不是只看电影，也要剧集","不是只看剧集，也要电影","不是只看电影"}) {
   var p=extract(text,"{\"intent\":\"search\",\"category\":\"tv\"}");
   assertThat(p.path("intent").asText()).as(text).isEqualTo("clarify");assertThat(p.has("category")).isFalse();
   assertThat(p.path("clarification").asText()).isNotBlank();
  }
  for(String text:new String[]{"不是只用豆瓣，也要IMDb","不是只按IMDb，还要豆瓣","不是只用豆瓣"}) {
   var p=extract(text,"{\"intent\":\"search\",\"ratingSource\":\"imdb\"}");
   assertThat(p.path("intent").asText()).as(text).isEqualTo("clarify");assertThat(p.has("ratingSource")).isFalse();
   assertThat(p.path("clarification").asText()).isNotBlank();
  }
  assertThat(extract("不是电影，只要剧集","{\"intent\":\"search\",\"category\":\"mv\"}").path("category").asText()).isEqualTo("tv");
  assertThat(extract("不是豆瓣，只用IMDb","{\"intent\":\"search\",\"ratingSource\":\"douban\"}").path("ratingSource").asText()).isEqualTo("imdb");
 }


 @Test void explicitVersionEnumerationOverridesRestatementClarificationWithoutInventingAVersion()throws Exception{
  for(String text:new String[]{"片库里的《远岸》是不是有多个电影版本？请用年份和导演区分","列出《旧城》的不同版本，写出各自年份和导演"}){
   var p=extract(text,"{\"intent\":\"clarify\",\"clarification\":\"请确认是否查不同版本\",\"semanticText\":\"版本\",\"movieId\":\"invented\"}");
   assertThat(p.path("intent").asText()).as(text).isEqualTo("search");assertThat(p.has("clarification")).isFalse();assertThat(p.has("movieId")).isFalse();assertThat(p.has("semanticText")).isFalse();
   assertThat(p.path("titles").size()).isEqualTo(1);assertThat(p.has("yearFrom")).isFalse();
  }
  var p=extract("远岸是不是有多个电影版本？","{\"intent\":\"clarify\",\"titles\":[\"远岸\"]}");
  assertThat(p.path("intent").asText()).isEqualTo("search");
  p=extract("联网查《旧城》的不同版本，列出年份","{\"intent\":\"clarify\"}");
  assertThat(p.path("intent").asText()).isEqualTo("web");
 }
 @Test void exactVersionMetadataFollowupRetainsKnownSubjectAndLiteralYear()throws Exception{
  var r=m.createObjectNode().put("message","只查2019年那个版本的主演，请按片库资料回答");r.putObject("context").putObject("queryPlan").putArray("titles").add("旧城");
  var p=planner.grounded(r,(ObjectNode)m.readTree("{\"intent\":\"clarify\",\"clarification\":\"请确认年份\"}"));
  assertThat(p.path("intent").asText()).isEqualTo("details");assertThat(p.path("inheritContext").asBoolean()).isTrue();assertThat(p.has("clarification")).isFalse();
  assertThat(p.path("yearFrom").asInt()).isEqualTo(2019);assertThat(p.path("yearTo").asInt()).isEqualTo(2019);assertThat(p.has("movieId")).isFalse();
  p=extract("《雨港》的导演是谁，主演有哪些？","{\"intent\":\"clarify\",\"clarification\":\"请确认要查导演还是主演\"}");
  assertThat(p.path("intent").asText()).isEqualTo("details");assertThat(p.has("clarification")).isFalse();
  p=extract("查《雨港》的主演，只要最多80分钟且至少120分钟的版本","{\"intent\":\"search\"}");
  assertThat(p.path("intent").asText()).isEqualTo("clarify");assertThat(p.path("clarification").asText()).contains("最低要求超过最高限制");
 }
 @Test void explicitAnnouncementRequestAllowsReadOnlyWebAndDoesNotFilterMovieProductionRegion()throws Exception{
  for(String text:new String[]{"截至今天，《远岸》有没有官方重映公告？给出来源和发布日期","请查《旧城》是否在中国大陆官方宣布重映，注明来源日期"}){
   var p=extract(text,"{\"intent\":\"clarify\",\"clarification\":\"请确认允许联网\",\"regionsAny\":[\"中国大陆\"],\"semanticText\":\"重映\"}");
   assertThat(p.path("intent").asText()).as(text).isEqualTo("web");assertThat(p.has("clarification")).isFalse();assertThat(p.has("regionsAny")).isFalse();assertThat(p.has("semanticText")).isFalse();
   assertThat(p.path("unsupportedConditions").toString()).contains("实际官方来源核验");
  }
  assertThat(extract("不用查公告，只列出《旧城》的不同版本和年份","{\"intent\":\"search\"}").path("intent").asText()).isEqualTo("search");
  assertThat(extract("查《官方重映公告发布日期》的导演是谁","{\"intent\":\"search\"}").path("intent").asText()).isEqualTo("details");
 }
 @Test void documentaryAliasesRetainMovieCategoryAndDoNotBecomeTitleInstructions()throws Exception{
  for(String noun:new String[]{"纪录电影","纪录影片","纪录片"}){
   var p=extract("找一部"+noun,"{\"intent\":\"search\",\"genresAll\":[\"纪录片\"]}");
   assertThat(p.path("genresAll")).containsExactly(m.getNodeFactory().textNode("纪录片"));
  }
  assertThat(extract("查《纪录电影》的导演是谁","{\"intent\":\"search\",\"genresAll\":[\"纪录片\"]}").path("genresAll").isEmpty()).isTrue();
 }
 @Test void languageOrKeepsBothOriginalTermsWithoutInventingCountryAndMarksUnverifiedVoice()throws Exception{
  var p=extract("找中文或西班牙语电影，必须是原始对白，不是发行配音","{\"intent\":\"search\",\"regionsAny\":[\"西班牙\"],\"languagesAny\":[\"西班牙语\"]}");
  assertThat(p.path("languagesAny")).containsExactly(m.getNodeFactory().textNode("中文"),m.getNodeFactory().textNode("西班牙语"));assertThat(p.path("regionsAny").isEmpty()).isTrue();
  assertThat(p.path("unsupportedConditions").toString()).contains("未区分原始对白与发行配音");
  p=extract("找西班牙电影，语言为中文或西班牙语","{\"intent\":\"search\"}");
  assertThat(p.path("regionsAny")).containsExactly(m.getNodeFactory().textNode("西班牙"));
  assertThat(extract("查《西班牙语》的年份","{\"intent\":\"search\",\"regionsAny\":[\"西班牙\"],\"languagesAny\":[\"西班牙语\"]}").path("languagesAny").isEmpty()).isTrue();
 }
 @Test void sceneExclusionsRemainUnknownEvenWhenOrdinaryGenreIsSupported()throws Exception{
  for(String condition:new String[]{"必须没有动物受伤镜头","不含儿童死亡画面","排除自残场景","避开虐待情节"}){
   var p=extract("找纪录电影，"+condition+"；没有逐场指南就不要保证","{\"intent\":\"search\"}");
   assertThat(p.path("genresAll")).containsExactly(m.getNodeFactory().textNode("纪录片"));assertThat(p.path("unsupportedConditions").toString()).as(condition).contains("普通简介不能逐场核验");
  }
  assertThat(extract("找关于受伤动物的纪录电影，不要恐怖电影","{\"intent\":\"search\"}").path("unsupportedConditions").isEmpty()).isTrue();
  assertThat(extract("查《不要动物受伤镜头》的资料","{\"intent\":\"search\"}").path("unsupportedConditions").isEmpty()).isTrue();
 }


 @Test void namedOriginalAuthorDoesNotBecomeAnActorOrDirectorFilter()throws Exception{
  var p=extract("找柳青原著改编的电影，最多三部；这里说的是原作者，不是查他当主演或导演的作品","{\"intent\":\"search\",\"actors\":[\"柳青\"],\"directorsAny\":[\"柳青\"]}");
  assertThat(p.path("actors").isEmpty()).isTrue();assertThat(p.path("directorsAny").isEmpty()).isTrue();
  assertThat(p.path("semanticText").asText()).contains("柳青原著改编");assertThat(p.path("unsupportedConditions").toString()).contains("原作改编关系","实际来源核验");
  p=extract("找作者柳青原著改编，不是演员柳青的作品","{\"intent\":\"search\",\"actors\":[\"柳青\"]}");
  assertThat(p.path("actors").isEmpty()).isTrue();assertThat(p.path("excludedActors").isEmpty()).isTrue();
 }
 @Test void authorRoleDoesNotEraseOtherExplicitActorsOrDirectors()throws Exception{
  var p=extract("找柳青原著改编、演员赵甲主演、钱乙导演的电影","{\"intent\":\"search\",\"actors\":[\"柳青\",\"赵甲\"],\"directors\":[\"钱乙\"]}");
  assertThat(p.path("actors")).containsExactly(m.getNodeFactory().textNode("赵甲"));assertThat(p.path("directors")).containsExactly(m.getNodeFactory().textNode("钱乙"));
  p=extract("找作家柳青原著改编而且柳青主演的电影","{\"intent\":\"search\",\"actors\":[\"柳青\"]}");
  assertThat(p.path("actors")).containsExactly(m.getNodeFactory().textNode("柳青"));
 }
 @Test void explicitWriterRoleIsNotSilentlyTreatedAsPerformingOrDirecting()throws Exception{
  var p=extract("找编剧程甲的故事改编作品，演员孙乙出演","{\"intent\":\"search\",\"actorsAny\":[\"程甲\",\"孙乙\"],\"directors\":[\"程甲\"]}");
  assertThat(p.path("actorsAny")).containsExactly(m.getNodeFactory().textNode("孙乙"));assertThat(p.path("directors").isEmpty()).isTrue();
 }
 @Test void positiveModifierBeforeDocumentaryGenreRestoresOmittedThemeWithoutAddingExcludedScenes()throws Exception{
  for(String subject:new String[]{"野外徒步","海底考古","冰川测绘"}){
   var p=extract("想看"+subject+"纪录片，但必须没有动物受伤的镜头，没有逐场指南就不要保证","{\"intent\":\"search\"}");
   assertThat(p.path("semanticText").asText()).isEqualTo(subject);assertThat(p.path("genresAll")).containsExactly(m.getNodeFactory().textNode("纪录片"));
   assertThat(p.path("unsupportedConditions").toString()).contains("普通简介不能逐场核验");
  }
  assertThat(extract("找动画电影，最多两部","{\"intent\":\"search\"}").has("semanticText")).isFalse();
  assertThat(extract("找两部纪录片","{\"intent\":\"search\"}").has("semanticText")).isFalse();
  assertThat(extract("找英语科幻电影，评分至少8分","{\"intent\":\"search\"}").has("semanticText")).isFalse();
 }
 @Test void softPreferenceNeverReplacesTheDeclaredCoreTheme()throws Exception{
  var p=extract("想看以小镇学校为故事重要场景的剧情电影。最好结尾轻松，但这只是偏好","{\"intent\":\"search\",\"semanticText\":\"小镇学校，最好结尾轻松\"}");
  assertThat(p.path("semanticText").asText()).contains("小镇学校","重要场景").doesNotContain("轻松","最好");
  assertThat(p.path("genresAll")).containsExactly(m.getNodeFactory().textNode("剧情"));
 }
 @Test void withdrawnEndingPreferenceKeepsThePreviousThemeAndExplicitlyUnchangedGenres()throws Exception{
  var r=m.createObjectNode().put("message","结尾不用温暖也行，但电影院必须是核心场景，电影类型和最多三部都别改。");
  var previous=r.putObject("context").putObject("queryPlan");previous.put("intent","search").put("semanticText","以小镇电影院为故事重要场景的剧情电影，最好结尾温暖").put("category","mv").put("limit",3);previous.putArray("genresAll").add("剧情");
  var p=planner.grounded(r,(ObjectNode)m.readTree("{\"intent\":\"search\",\"semanticText\":\"小镇电影院，温暖结尾\",\"genresAll\":[\"爱情\"]}"));
  assertThat(p.path("inheritContext").asBoolean()).isTrue();assertThat(p.path("semanticText").asText()).contains("小镇电影院","电影院必须是核心场景").doesNotContain("温暖","最好");
  assertThat(p.path("genresAll")).containsExactly(m.getNodeFactory().textNode("剧情"));assertThat(p.path("category").asText()).isEqualTo("mv");assertThat(p.path("limit").asInt()).isEqualTo(3);
 }
 @Test void preferenceWithdrawalUsesTheCurrentWordsRatherThanAStoredMoodKeyword()throws Exception{
  var r=m.createObjectNode().put("message","氛围不需要轻松也可以，保持上一轮类型和数量。");
  var previous=r.putObject("context").putObject("queryPlan");previous.put("intent","search").put("semanticText","乡村学校的故事；轻松氛围").put("category","mv").put("limit",2);previous.putArray("genresAll").add("剧情");
  var p=planner.grounded(r,(ObjectNode)m.readTree("{\"intent\":\"search\",\"semanticText\":\"乡村学校，轻松\"}"));
  assertThat(p.path("inheritContext").asBoolean()).isTrue();assertThat(p.path("semanticText").asText()).contains("乡村学校").doesNotContain("轻松");
 }
 @Test void explicitThemeReplacementDoesNotKeepTheOldNarrativeSetting()throws Exception{
  var r=m.createObjectNode().put("message","主题改成海岛救援的故事，类型别改。");
  var previous=r.putObject("context").putObject("queryPlan");previous.put("intent","search").put("semanticText","乡村学校成长");previous.putArray("genresAll").add("剧情");
  var p=planner.grounded(r,(ObjectNode)m.readTree("{\"intent\":\"search\",\"semanticText\":\"乡村学校成长\"}"));
  assertThat(p.path("semanticText").asText()).contains("海岛救援").doesNotContain("乡村学校");assertThat(p.path("genresAll")).containsExactly(m.getNodeFactory().textNode("剧情"));
 }

}
