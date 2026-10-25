"""Build a public movie-metadata float32 index with resumable, model-specific CPU embedding cache."""
import argparse, array, hashlib, json, math, os, re, shutil, struct, sys, time, urllib.request, urllib.error
from pathlib import Path

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        raise ValueError('Redirect refused; no credential forwarding')

def checked_origin(url):
    from urllib.parse import urlparse
    value=urlparse(url)
    if value.scheme!='http' or value.hostname not in ['127.0.0.1','localhost','::1'] or not value.port or value.username or value.password or value.query or value.fragment or value.path not in ['', '/']:
        raise ValueError('Origin must be a credential-free loopback HTTP address')
    return url.rstrip('/')

def request(url,body=None,headers=None,timeout=240):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    data=None if body is None else json.dumps(body,ensure_ascii=False).encode('utf-8')
    with opener.open(urllib.request.Request(url,data=data,headers={'Content-Type':'application/json',**(headers or {})}),timeout=timeout) as response:
        raw=response.read(3*1024*1024+1)
        if len(raw)>3*1024*1024:raise ValueError("Response exceeds size limit")
        return json.loads(raw)

def catalog_page(url,token,page):
    # Retry only this read-only fixed-origin export, honoring the server's bounded delay.
    for attempt in range(3):
        try:
            return request(url,headers={'X-AI-Bridge-Token':token},timeout=30)
        except urllib.error.HTTPError as error:
            delay=error.headers.get('Retry-After','')
            if error.code!=429 or attempt==2 or not delay.isdigit() or not 0<=int(delay)<=60:
                raise
            seconds=max(1,int(delay))
            print(json.dumps({'catalogRateLimited':True,'page':page,'waitSeconds':seconds}),flush=True)
            time.sleep(seconds)

def documents(catalog,base_url,token_file):
    if catalog:
        raw=json.loads(Path(catalog).read_text(encoding='utf-8'))['movies'];docs=[]
        for movie in raw:
            def value(k): return movie.get(k) or ''
            def values(k):
                a=movie.get(k) or []
                return json.loads(a) if isinstance(a,str) else a
            text='\n'.join([value('title_cn'),value('title_en'),value('aliases'),value('series_name'),*[json.dumps(values(k),ensure_ascii=False,separators=(',',':')) for k in ['genres','directors','actors']],value('summary')])
            text=text.encode('utf-16-le')[:4400].decode('utf-16-le',errors='ignore')
            docs.append({'id':movie['id'],'titleCn':value('title_cn'),'titleEn':value('title_en'),'embeddingText':text,'fingerprint':hashlib.sha256(text.encode()).hexdigest()})
        return docs
    base_url=checked_origin(base_url)
    token=Path(token_file).read_text().strip();docs=[]
    for page in range(1,501):
        data=catalog_page(base_url.rstrip('/')+f'/api/internal/ai/catalog/documents?page={page}&size=100',token,page)
        docs.extend(data['documents'])
        if len(docs)>=data['total'] or not data['documents']:break
        time.sleep(1)  # Export coexists with the two-second bridge heartbeat.
    return docs

def valid_cache(path):
    try:
        if path.stat().st_size>16384:return False
        raw=path.read_bytes()
        if len(raw)%4 or not 32<=len(raw)//4<=4096:return False
        values=struct.unpack('>'+str(len(raw)//4)+'f',raw)
        return all(math.isfinite(x) for x in values) and .9<=sum(x*x for x in values)<=1.1
    except (OSError,struct.error):return False

def packed(values):
    vector=array.array('f',values)
    if sys.byteorder=='little':vector.byteswap()
    return vector.tobytes()

def text(stream,value):
    data=value.encode('utf-8');stream.write(struct.pack('>i',len(data)));stream.write(data)

def main():
    p=argparse.ArgumentParser();p.add_argument('--catalog');p.add_argument('--base-url',default='http://127.0.0.1:8880');p.add_argument('--token-file');p.add_argument('--model',default='bge-m3');p.add_argument('--ollama',default='http://127.0.0.1:11434');p.add_argument('--output',type=Path,required=True);p.add_argument('--batch',type=int,default=8);p.add_argument('--num-batch',type=int,choices=[256,512,1024],default=512);p.add_argument('--run',action='store_true');p.add_argument('--gpu-build',action='store_true',help='Use GPU only for supervised initial build; queries remain CPU');args=p.parse_args()
    if not args.run:print(json.dumps({'mode':'plan','model':args.model,'cpuOnly':not args.gpu_build,'numBatch':args.num_batch,'output':str(args.output)}));return
    if not 1<=args.batch<=32:raise ValueError('Invalid batch')
    args.ollama=checked_origin(args.ollama)
    docs=documents(args.catalog,args.base_url,args.token_file);ids=[d['id'] for d in docs]
    if not docs or len(docs)>50000 or len(set(ids))!=len(ids):raise ValueError('Invalid catalog')
    for doc in docs:
        if not re.fullmatch('[A-Za-z0-9_-]{1,64}',str(doc['id'])) or not re.fullmatch('[a-f0-9]{64}',doc['fingerprint']):raise ValueError('Invalid catalog identity')
        if len(doc['embeddingText'])>2200 or hashlib.sha256(doc['embeddingText'].encode()).hexdigest()!=doc['fingerprint']:raise ValueError('Catalog text fingerprint mismatch')
    tags=request(args.ollama+'/api/tags');model=next(x for x in tags['models'] if x['name'] in [args.model,args.model+':latest']);digest=model['digest']
    if not re.fullmatch('[a-f0-9]{64}',digest):raise ValueError('Invalid model digest')
    cache=args.output.parent/'semantic-cache'/digest;cache.mkdir(parents=True,exist_ok=True);started=time.monotonic();dimensions=None;rows=[]
    for offset in range(0,len(docs),args.batch):
        batch=docs[offset:offset+args.batch];missing=[d for d in batch if not valid_cache(cache/(d['fingerprint']+'.bin'))]
        if missing:
            result=request(args.ollama+'/api/embed',{'model':args.model,'input':[d['embeddingText'] for d in missing],'options':{'num_gpu':99 if args.gpu_build else 0,'num_ctx':4096,'num_batch':args.num_batch},'truncate':False,'keep_alive':'5m'},timeout=240)
            if len(result['embeddings'])!=len(missing):raise ValueError('Invalid embedding count')
            for doc,vector in zip(missing,result['embeddings']):
                if not 32<=len(vector)<=4096 or not all(math.isfinite(x) for x in vector):raise ValueError('Invalid embedding vector')
                norm=math.sqrt(sum(x*x for x in vector))
                if norm<.001:raise ValueError('Empty embedding')
                final=cache/(doc['fingerprint']+'.bin');temporary=final.with_suffix('.tmp');temporary.write_bytes(packed([x/norm for x in vector]));os.replace(temporary,final)
        for doc in batch:
            size=(cache/(doc['fingerprint']+'.bin')).stat().st_size
            if dimensions is None:dimensions=size//4
            if not 32<=dimensions<=4096 or size%4:raise ValueError('Invalid cached embedding dimensions')
            if size!=dimensions*4:raise ValueError('Embedding dimension mismatch')
        if offset%(args.batch*20)==0:print(json.dumps({'completed':min(offset+len(batch),len(docs)),'total':len(docs),'elapsedSeconds':round(time.monotonic()-started)}),flush=True)
    current=request(args.ollama+'/api/tags');current_model=next(x for x in current['models'] if x['name'] in [args.model,args.model+':latest'])
    if current_model['digest']!=digest:raise ValueError('Model changed during indexing; no publication')
    args.output.parent.mkdir(parents=True,exist_ok=True);temporary=args.output.with_suffix('.partial')
    header={'version':2,'model':args.model,'modelDigest':digest,'dimensions':dimensions,'count':len(docs),'catalogHash':hashlib.sha256(''.join(d['id']+d['fingerprint'] for d in docs).encode()).hexdigest()}
    with temporary.open('wb') as stream:
        stream.write(b'GYINGSEM2');text(stream,json.dumps(header,separators=(',',':')))
        for doc in docs:
            for key in ['id','fingerprint','titleCn','titleEn','embeddingText']:text(stream,doc.get(key,''))
            stream.write((cache/(doc['fingerprint']+'.bin')).read_bytes())
        stream.flush();os.fsync(stream.fileno())
    if args.output.exists():
        backup=args.output.with_name(args.output.name+'.before-'+str(time.time_ns()));shutil.copy2(args.output,backup)
    os.replace(temporary,args.output)
    request(args.ollama+'/api/embed',{'model':args.model,'input':'','keep_alive':0,'options':{'num_gpu':99 if args.gpu_build else 0}})
    print(json.dumps({'status':'complete',**header,'seconds':round(time.monotonic()-started),'fileSha256':hashlib.sha256(args.output.read_bytes()).hexdigest()}),flush=True)
if __name__=='__main__':main()
