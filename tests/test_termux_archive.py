import importlib.util, tempfile, unittest, pathlib, os, json, hashlib, types
spec=importlib.util.spec_from_file_location('files_bridge',pathlib.Path(__file__).parents[1]/'app/src/main/assets/termux/files_bridge.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.temp.name)/'files';self.root.mkdir();self.remote={};self.fail=False;self.bad=False
        def run(args,stdin=None,**kwargs):
            if self.fail:return types.SimpleNamespace(returncode=1,stdout=b'')
            if args[1]=='rcat':
                data=b''
                while True:
                    block=os.read(stdin,4096)
                    if not block:break
                    data+=block
                self.remote[args[2]]=data;return types.SimpleNamespace(returncode=0,stdout=b'')
            data=self.remote[args[2]];body={'Size':len(data),'Hashes':{'MD5':'bad' if self.bad else hashlib.md5(data).hexdigest()},'IsDir':False}
            return types.SimpleNamespace(returncode=0,stdout=json.dumps(body).encode())
        self.archive=mod.Archive({'root':str(self.root),'state':str(pathlib.Path(self.temp.name)/'state'),'remote':'drive:','device':'tablet'},run)
        self.file=self.root/'prova con spazi.txt';self.file.write_text('Contenuto è Unicode\n')
    def tearDown(self):self.temp.cleanup()
    def test_copy_then_verified_removal(self):
        job=self.archive.copy(self.file.name);self.assertTrue(self.file.exists());self.assertEqual('verified',self.archive.rows()[0][2]);self.archive.remove_verified(job);self.assertFalse(self.file.exists());self.assertEqual('removed',self.archive.rows()[0][2]);self.assertEqual(1,len(self.remote))
    def test_drive_error_keeps_original(self):
        self.fail=True
        with self.assertRaises(RuntimeError):self.archive.copy(self.file.name)
        self.assertTrue(self.file.exists())
    def test_checksum_error_keeps_original(self):
        self.bad=True
        with self.assertRaises(RuntimeError):self.archive.copy(self.file.name)
        self.assertTrue(self.file.exists());self.assertEqual('failed',self.archive.rows()[0][2])
    def test_changed_original_is_never_removed(self):
        job=self.archive.copy(self.file.name);self.file.write_text('nuovo contenuto')
        with self.assertRaises(RuntimeError):self.archive.remove_verified(job)
        self.assertEqual('nuovo contenuto',self.file.read_text())
    def test_remote_rechecked_before_removal(self):
        job=self.archive.copy(self.file.name);self.bad=True
        with self.assertRaises(RuntimeError):self.archive.remove_verified(job)
        self.assertTrue(self.file.exists())
    def test_path_escape_and_symlinks_denied(self):
        (self.root/'link').symlink_to(self.file);(self.root/'folder').symlink_to(self.root,target_is_directory=True)
        for path in ['../file',str(self.file),'link','folder/'+self.file.name,'.private','Android/file']:
            with self.assertRaises((ValueError,OSError)):
                with self.archive.opened(path):pass
    def test_duplicate_deletion_refused(self):
        job=self.archive.copy(self.file.name);self.archive.remove_verified(job)
        with self.assertRaises(ValueError):self.archive.remove_verified(job)
    def test_second_copy_uses_distinct_destination(self):
        self.archive.copy(self.file.name);self.archive.copy(self.file.name);self.assertEqual(2,len(self.remote));self.assertTrue(self.file.exists())
class HttpTests(unittest.TestCase):
    setUp=ArchiveTests.setUp
    tearDown=ArchiveTests.tearDown
    def test_loopback_form_origin_and_token(self):
        import threading, urllib.request, urllib.error
        server=mod.make_server(self.archive,0);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();port=server.server_address[1];url=f'http://127.0.0.1:{port}/files'
        try:
            with urllib.request.urlopen(url) as response:body=response.read().decode();self.assertIn('Copia su Drive e verifica',body)
            for origin in ['https://example.com','null']:
                request=urllib.request.Request(url+'/copy',data=b'csrf=wrong&path=test',headers={'Origin':origin,'Content-Type':'application/x-www-form-urlencoded'})
                with self.assertRaises(urllib.error.HTTPError) as caught:urllib.request.urlopen(request)
                self.assertEqual(403,caught.exception.code)
            request=urllib.request.Request(url+'/copy',data=b'csrf=wrong&path=test',headers={'Origin':f'http://127.0.0.1:{port}','Content-Type':'application/x-www-form-urlencoded'})
            with self.assertRaises(urllib.error.HTTPError) as caught:urllib.request.urlopen(request)
            self.assertEqual(409,caught.exception.code);self.assertTrue(self.file.exists());self.assertEqual(0,len(self.remote))
        finally:server.shutdown();server.server_close();thread.join()
if __name__=='__main__':unittest.main()
