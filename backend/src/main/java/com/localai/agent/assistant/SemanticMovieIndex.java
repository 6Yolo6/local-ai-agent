package com.localai.agent.assistant;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.localai.agent.ollama.OllamaService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.text.Normalizer;

/** Atomically published, model-specific float32 index; queries alone call CPU embeddings. */
@Service
public class SemanticMovieIndex {
    private final OllamaService model;private final ObjectMapper mapper;private final String filename,embeddingModel;
    private volatile Snapshot cached;private volatile long modified=-1;
    private record Entry(String id,String fingerprint,String title,String english,String text,float[] vector){}
    private record Snapshot(int dimensions,String digest,List<Entry> entries){}
    public SemanticMovieIndex(OllamaService model,ObjectMapper mapper,@Value("${LOCAL_AI_GYING_SEMANTIC_INDEX:}")String filename,@Value("${LOCAL_AI_EMBEDDING_MODEL:bge-m3}")String embeddingModel){this.model=model;this.mapper=mapper;this.filename=filename;this.embeddingModel=embeddingModel;}
    public boolean available(){return !filename.isBlank()&&Files.isRegularFile(Path.of(filename));}
    private synchronized Snapshot snapshot()throws IOException{
        Path file=Path.of(filename);long mtime=Files.getLastModifiedTime(file).toMillis();if(cached!=null&&mtime==modified)return cached;
        if(Files.size(file)>512L*1024*1024)throw new IOException("Semantic index too large");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
            byte[] magic=in.readNBytes(9);if(!Arrays.equals(magic,"GYINGSEM2".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IOException("Invalid semantic index header");
            JsonNode header=mapper.readTree(readText(in,20000));int dims=header.path("dimensions").asInt(),count=header.path("count").asInt();
            String digest=header.path("modelDigest").asText();
            if(header.path("version").asInt()!=2||!embeddingModel.equals(header.path("model").asText())||!digest.matches("[a-f0-9]{64}")||dims<32||dims>4096||count<1||count>50000)throw new IOException("Semantic model/index mismatch");
            List<Entry> rows=new ArrayList<>();Set<String> seen=new HashSet<>();for(int n=0;n<count;n++){
                String id=readText(in,100),hash=readText(in,100),title=readText(in,1000),english=readText(in,1000),text=readText(in,12000);
                if(!id.matches("[A-Za-z0-9_-]{1,64}")||!hash.matches("[a-f0-9]{64}")||!seen.add(id))throw new IOException("Invalid semantic document");
                float[] vector=new float[dims];double norm=0;for(int j=0;j<dims;j++){vector[j]=in.readFloat();if(!Float.isFinite(vector[j]))throw new IOException("Invalid semantic vector");norm+=vector[j]*vector[j];}
                if(norm<.9||norm>1.1)throw new IOException("Semantic vectors are not normalized");rows.add(new Entry(id,hash,title,english,text,vector));
            }
            if(in.read()!=-1)throw new IOException("Trailing semantic index data");cached=new Snapshot(dims,digest,List.copyOf(rows));modified=mtime;return cached;
        }
    }
    private String readText(DataInputStream in,int max)throws IOException{int length=in.readInt();if(length<0||length>max)throw new IOException("Invalid semantic string");byte[] data=in.readNBytes(length);if(data.length!=length)throw new EOFException();return new String(data,java.nio.charset.StandardCharsets.UTF_8);}
    private static String key(String v){return Normalizer.normalize(v,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]","");}
    public ArrayNode candidates(JsonNode plan,long deadline)throws IOException{
        ArrayNode output=mapper.createArrayNode();if(!available())return output;Snapshot data=snapshot();String text=plan.path("semanticText").asText();String seed=plan.path("seedTitle").asText();
        if(!seed.isBlank())for(Entry e:data.entries)if(key(seed).equals(key(e.title))||key(seed).equals(key(e.english))){text=e.text+" "+text;break;}
        if(text.isBlank())return output;text=text.substring(0,Math.min(text.length(),2200));
        if(!data.digest.equals(model.modelDigest(embeddingModel)))throw new IOException("Semantic model digest changed; rebuild required");
        JsonNode embedding=model.embed(embeddingModel,List.of(text),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())).get(0);
        if(!data.digest.equals(model.modelDigest(embeddingModel)))throw new IOException("Semantic model changed during query");
        if(embedding.size()!=data.dimensions)throw new IOException("Embedding dimensions changed");double[] vector=new double[data.dimensions];double norm=0;
        for(int i=0;i<vector.length;i++){vector[i]=embedding.path(i).asDouble(Double.NaN);if(!Double.isFinite(vector[i]))throw new IOException("Invalid query embedding");norm+=vector[i]*vector[i];}
        if(norm<.0001)throw new IOException("Empty query embedding");double scale=Math.sqrt(norm);for(int i=0;i<vector.length;i++)vector[i]/=scale;
        record Scored(Entry entry,double score){}PriorityQueue<Scored> best=new PriorityQueue<>(Comparator.comparingDouble(Scored::score));
        for(Entry e:data.entries){double score=0;for(int i=0;i<vector.length;i++)score+=vector[i]*e.vector[i];best.add(new Scored(e,score));if(best.size()>100)best.poll();}
        List<Scored> sorted=new ArrayList<>(best);sorted.sort(Comparator.comparingDouble(Scored::score).reversed());for(Scored s:sorted)output.addObject().put("id",s.entry.id).put("fingerprint",s.entry.fingerprint).put("score",Math.max(-1,Math.min(1,s.score)));return output;
    }
}
