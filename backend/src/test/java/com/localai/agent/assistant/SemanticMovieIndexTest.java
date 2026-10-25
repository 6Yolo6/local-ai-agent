package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.localai.agent.ollama.OllamaService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticMovieIndexTest {
    @TempDir Path directory;final ObjectMapper mapper=new ObjectMapper();OllamaService model;Path file;
    void text(DataOutputStream out,String value)throws Exception{byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    @BeforeEach void setup()throws Exception{
        model=mock(OllamaService.class);when(model.modelDigest("bge-m3")).thenReturn("a".repeat(64));var vector=mapper.createArrayNode();for(int i=0;i<32;i++)vector.add(i==0?1:0);when(model.embed(anyString(),anyList(),anyLong())).thenReturn(mapper.createArrayNode().add(vector));
        file=directory.resolve("catalog.bin");try(var out=new DataOutputStream(Files.newOutputStream(file))){out.writeBytes("GYINGSEM2");text(out,mapper.createObjectNode().put("version",2).put("model","bge-m3").put("modelDigest","a".repeat(64)).put("dimensions",32).put("count",1).toString());for(String value:List.of("a","b".repeat(64),"样本","sample","公开剧情"))text(out,value);for(int i=0;i<32;i++)out.writeFloat(i==0?1:0);}
    }
    SemanticMovieIndex index(){return new SemanticMovieIndex(model,mapper,file.toString(),"bge-m3");}
    ObjectNode plan(){return mapper.createObjectNode().put("semanticText","宇航员回家");}
    @Test void readsNormalizedVectorsAndReturnsFingerprint()throws Exception{var result=index().candidates(plan(),System.nanoTime()+10_000_000_000L);assertThat(result.get(0).path("id").asText()).isEqualTo("a");assertThat(result.get(0).path("score").asDouble()).isEqualTo(1);assertThat(result.get(0).path("fingerprint").asText()).isEqualTo("b".repeat(64));}
    @Test void changedDigestDisablesIndexBeforeEmbedding()throws Exception{when(model.modelDigest("bge-m3")).thenReturn("c".repeat(64));assertThatThrownBy(()->index().candidates(plan(),System.nanoTime()+10_000_000_000L)).isInstanceOf(IOException.class);verify(model,never()).embed(anyString(),anyList(),anyLong());}
    @Test void corruptAndTrailingDataCannotBeLoaded()throws Exception{Files.write(file,new byte[]{1},StandardOpenOption.APPEND);assertThatThrownBy(()->index().candidates(plan(),System.nanoTime()+10_000_000_000L)).isInstanceOf(IOException.class);}
    @Test void mismatchedEmbeddingDimensionsAreRejected()throws Exception{when(model.embed(anyString(),anyList(),anyLong())).thenReturn(mapper.createArrayNode().add(mapper.createArrayNode().add(1)));assertThatThrownBy(()->index().candidates(plan(),System.nanoTime()+10_000_000_000L)).isInstanceOf(IOException.class);}
    @Test void missingIndexAllowsLexicalFallback()throws Exception{Files.delete(file);assertThat(index().candidates(plan(),System.nanoTime()).isEmpty()).isTrue();verifyNoInteractions(model);}
}
