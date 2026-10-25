import importlib.util
from pathlib import Path
import tempfile
import unittest
import struct
import json

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
    def test_binary_vectors_are_big_endian(self):self.assertEqual(struct.unpack('>2f',index.packed([1,.5])),(1,.5))

if __name__=='__main__':unittest.main()
