import importlib.util
from pathlib import Path
import tempfile
import unittest
import struct
import json
from unittest.mock import patch
from email.message import Message
from urllib.error import HTTPError

spec=importlib.util.spec_from_file_location('index',Path(__file__).parents[1]/'build-semantic-index.py')
index=importlib.util.module_from_spec(spec);spec.loader.exec_module(index)

class SemanticIndexBuilder(unittest.TestCase):
    def test_only_credential_free_loopback_origins_are_accepted(self):
        self.assertEqual(index.checked_origin('http://127.0.0.1:11434/'),'http://127.0.0.1:11434')
        for url in ['http://example.com:80','https://127.0.0.1:11434','http://user:password@127.0.0.1:11434','http://127.0.0.1:11434/path','http://127.0.0.1:11434?x=1']:
            with self.assertRaises(ValueError):index.checked_origin(url)
    def test_cache_requires_finite_normalized_bounded_vectors(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'cache.bin';self.assertFalse(index.valid_cache(p))
            p.write_bytes(index.packed([1]+[0]*31));self.assertTrue(index.valid_cache(p))
            for vector in [[float('nan')]+[0]*31,[0]*32,[1]*32,[1]]:
                p.write_bytes(index.packed(vector));self.assertFalse(index.valid_cache(p))
            p.write_bytes(b'x');self.assertFalse(index.valid_cache(p))
    def test_frozen_documents_use_metadata_and_content_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'catalog.json';p.write_text(json.dumps({'movies':[{'id':'a','title_cn':'样本','summary':'公开简介','genres':'["科幻"]','actors':'[]','directors':'[]'}]}),encoding='utf-8')
            docs=index.documents(p,None,None);self.assertEqual(len(docs),1);self.assertIn('公开简介',docs[0]['embeddingText']);self.assertEqual(docs[0]['fingerprint'],index.hashlib.sha256(docs[0]['embeddingText'].encode()).hexdigest())
    def test_redirects_cannot_forward_bridge_credential(self):
        with self.assertRaises(ValueError):index.NoRedirect().redirect_request(None,None,302,'Found',{},'https://example.com')
    def test_live_export_uses_allowed_page_size_and_stops_at_total(self):
        with tempfile.TemporaryDirectory() as tmp:
            token=Path(tmp)/'credential.txt';token.write_text('fixture-only')
            with patch.object(index,'catalog_page',side_effect=[{'total':3,'documents':[{'id':'a'},{'id':'b'}]},{'total':3,'documents':[{'id':'c'}]}]) as page, patch.object(index.time,'sleep') as sleep:
                self.assertEqual(len(index.documents(None,'http://127.0.0.1:8880',token)),3)
                self.assertEqual(page.call_count,2)
                self.assertIn('page=1&size=100',page.call_args_list[0].args[0]);sleep.assert_called_once_with(1)
    def test_export_honors_numeric_retry_after(self):
        headers=Message();headers['Retry-After']='2'
        error=HTTPError('http://127.0.0.1:8880',429,'limited',headers,None)
        with patch.object(index,'request',side_effect=[error,{'documents':[]}]) as request, patch.object(index.time,'sleep') as sleep:
            self.assertEqual(index.catalog_page('http://127.0.0.1:8880','fixture-only',3),{'documents':[]})
            self.assertEqual(request.call_count,2);sleep.assert_called_once_with(2)
    def test_export_retries_are_bounded(self):
        headers=Message();headers['Retry-After']='1'
        error=HTTPError('http://127.0.0.1:8880',429,'limited',headers,None)
        with patch.object(index,'request',side_effect=error) as request, patch.object(index.time,'sleep') as sleep:
            with self.assertRaises(HTTPError):index.catalog_page('http://127.0.0.1:8880','fixture-only',1)
            self.assertEqual(request.call_count,3);self.assertEqual(sleep.call_count,2)
    def test_export_does_not_retry_auth_failure_or_unbounded_delay(self):
        for code,delay in [(401,'1'),(429,'9999'),(429,'bad')]:
            headers=Message();headers['Retry-After']=delay
            error=HTTPError('http://127.0.0.1:8880',code,'rejected',headers,None)
            with patch.object(index,'request',side_effect=error) as request, patch.object(index.time,'sleep') as sleep:
                with self.assertRaises(HTTPError):index.catalog_page('http://127.0.0.1:8880','fixture-only',1)
                self.assertEqual(request.call_count,1);sleep.assert_not_called()
    def test_binary_vectors_are_big_endian(self):self.assertEqual(struct.unpack('>2f',index.packed([1,.5])),(1,.5))

if __name__=='__main__':unittest.main()
