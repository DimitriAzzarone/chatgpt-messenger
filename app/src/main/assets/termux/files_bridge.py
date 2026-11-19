#!/usr/bin/env python3
"""Loopback-only file archive companion; leaves Bridge 15 on port 8765 untouched."""
import argparse, contextlib, hashlib, hmac, html, json, os, secrets, sqlite3, stat, subprocess, threading, time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

class Archive:
    def __init__(self, config, runner=None):
        self.root=Path(config['root']).expanduser().resolve(strict=True)
        self.remote=config['remote'];self.device=config['device']
        if not self.root.is_dir() or not self.remote.endswith(':') or not self.remote[:-1].replace('_','').replace('-','').isalnum():raise ValueError('Radice o remote non valido')
        if not self.device.replace('-','').replace('_','').isalnum():raise ValueError('Nome dispositivo non valido')
        self.state=Path(config['state']).expanduser();self.state.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.runner=runner or subprocess.run;self.lock=threading.Lock()
        with self.db() as db:db.execute('CREATE TABLE IF NOT EXISTS backups(id TEXT PRIMARY KEY,path TEXT,remote TEXT,size INTEGER,md5 TEXT,identity TEXT,status TEXT)')
    def db(self):return sqlite3.connect(self.state/'archive.sqlite')
    @contextlib.contextmanager
    def opened(self, relative):
        parts=Path(relative).parts
        if not parts or Path(relative).is_absolute() or any(p in ('.','..') or p.startswith('.') or p.lower() in ('android','backup apk') for p in parts):raise ValueError('Percorso non consentito')
        parent=os.open(self.root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        fd=None
        try:
            for part in parts[:-1]:
                child=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent);os.close(parent);parent=child
            fd=os.open(parts[-1],os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=parent)
            if not stat.S_ISREG(os.fstat(fd).st_mode):raise ValueError('Solo file regolari')
            yield fd,parent,parts[-1]
        finally:
            if fd is not None:os.close(fd)
            os.close(parent)
    @staticmethod
    def identity(fd):
        s=os.fstat(fd);return [s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns]
    @staticmethod
    def digest(fd):
        os.lseek(fd,0,os.SEEK_SET);md5=hashlib.md5()
        while True:
            data=os.read(fd,1024*1024)
            if not data:break
            md5.update(data)
        os.lseek(fd,0,os.SEEK_SET);return md5.hexdigest()
    def run(self,args,stdin=None):
        result=self.runner(['rclone',*args],stdin=stdin,capture_output=True,timeout=7200)
        if result.returncode:raise RuntimeError('rclone non riuscito; originale conservato. Controlla accesso Drive e connessione.')
        return result.stdout
    def verify(self,remote,size,md5):
        item=json.loads(self.run(['lsjson',remote,'--stat','--hash']))
        if item.get('IsDir') or item.get('Size')!=size or item.get('Hashes',{}).get('MD5','').lower()!=md5:raise RuntimeError('Copia Drive non verificata: originale conservato')
    def copy(self,relative):
        with self.lock,self.opened(relative) as (fd,parent,name):
            before=self.identity(fd);md5=self.digest(fd);job=secrets.token_hex(12)
            remote=f'{self.remote}Dan-Archivio/{self.device}/{job}/{name}'
            with self.db() as db:db.execute('INSERT INTO backups VALUES(?,?,?,?,?,?,?)',(job,relative,remote,before[2],md5,json.dumps(before),'copying'))
            try:
                self.run(['rcat',remote,'--size',str(before[2]),'--immutable'],stdin=fd)
                if self.identity(fd)!=before:raise RuntimeError('File cambiato durante la copia: originale conservato')
                self.verify(remote,before[2],md5)
                status='verified'
            except Exception:
                with self.db() as db:db.execute('UPDATE backups SET status=? WHERE id=?',('failed',job))
                raise
            with self.db() as db:db.execute('UPDATE backups SET status=? WHERE id=?',(status,job))
            return job
    def remove_verified(self,job):
        with self.lock:
            with self.db() as db:row=db.execute('SELECT path,remote,size,md5,identity,status FROM backups WHERE id=?',(job,)).fetchone()
            if not row or row[5]!='verified':raise ValueError('Serve una copia verificata')
            relative,remote,size,md5,identity,_=row
            self.verify(remote,size,md5)
            with self.opened(relative) as (fd,parent,name):
                if self.identity(fd)!=json.loads(identity) or self.digest(fd)!=md5:raise RuntimeError('Originale modificato: cancellazione annullata')
                # Move to a local quarantine first; never unlink a substituted path.
                quarantine='.dan-archive-'+job
                reserve=os.open(quarantine,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600,dir_fd=parent);os.close(reserve)
                with self.db() as db:db.execute('UPDATE backups SET status=? WHERE id=?',('quarantine:'+quarantine,job))
                os.rename(name,quarantine,src_dir_fd=parent,dst_dir_fd=parent)
                moved=os.stat(quarantine,dir_fd=parent,follow_symlinks=False)
                current=os.fstat(fd)
                if (moved.st_dev,moved.st_ino)!=(current.st_dev,current.st_ino) or current.st_size!=size or self.digest(fd)!=md5:
                    # Retain both names rather than risk overwriting a newly created file.
                    raise RuntimeError('File cambiato: conservato in quarantena, nessuna eliminazione')
                os.unlink(quarantine,dir_fd=parent)
            with self.db() as db:db.execute('UPDATE backups SET status=? WHERE id=?',('removed',job))
    def rows(self):
        with self.db() as db:return db.execute('SELECT id,path,status FROM backups ORDER BY rowid DESC LIMIT 50').fetchall()
    def files(self):
        # Flat file listing: no recursive crawl of the device or private app data.
        return sorted(p.name for p in self.root.iterdir() if not p.name.startswith('.') and not p.is_symlink() and p.is_file())[:200]


def make_server(archive,port):
    csrf=secrets.token_urlsafe(32);origin=f'http://127.0.0.1:{port}';jobs=threading.Lock();last={'message':''}
    def esc(value):return html.escape(str(value),quote=True)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def page(self,message='',code=200):
            body='<h1>Dan · archivio '+esc(archive.device)+'</h1><p>Copia su Drive, verifica, poi libera spazio.</p>'
            body+='<p>'+esc(message or last['message'])+'</p>'
            body+='<form method="post" action="/files/copy"><input type="hidden" name="csrf" value="'+csrf+'"><label>File da copiare <input name="path" list="files" required></label><datalist id="files">'
            for name in archive.files():body+='<option>'+esc(name)+'</option>'
            body+='</datalist><button>Copia su Drive e verifica</button></form><h2>Copie</h2>'
            for job,name,status in archive.rows():
                body+='<p>'+esc(name)+' · '+esc(status)+'</p>'
                if status=='verified':body+='<form method="post" action="/files/remove"><input type="hidden" name="csrf" value="'+csrf+'"><input type="hidden" name="job" value="'+job+'"><button>Libera spazio: '+esc(name)+'</button></form>'
            body+='<p><a href="/files">Aggiorna stato</a> · <a href="http://127.0.0.1:8765/dev">Bridge 15</a></p>'
            data=('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Archivio Dan</title><style>body{background:#071428;color:#e0f4ff;font:18px sans-serif;max-width:800px;margin:auto;padding:20px}button,select{font:inherit;padding:12px;margin:8px}a{color:#00c8ff}</style>'+body).encode()
            self.send_response(code);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('Content-Security-Policy',"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");self.send_header('X-Content-Type-Options','nosniff');self.end_headers();self.wfile.write(data)
        def host_ok(self):return self.headers.get('Host')==f'127.0.0.1:{port}' and self.client_address[0]=='127.0.0.1'
        def do_GET(self):
            if not self.host_ok() or urlsplit(self.path).path not in ('/','/files','/health'):self.send_error(404);return
            if urlsplit(self.path).path=='/health':
                data=json.dumps({'service':'Dan Files','version':'1.1','device':archive.device}).encode()
                self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data);return
            self.page()
        def do_POST(self):
            if not self.host_ok() or self.headers.get('Origin')!=origin or self.headers.get('Content-Type','').split(';')[0]!='application/x-www-form-urlencoded':self.send_error(403);return
            try:
                size=int(self.headers.get('Content-Length','0'))
                if not 1<=size<=8192:raise ValueError('Richiesta non valida')
                data=parse_qs(self.rfile.read(size).decode(),max_num_fields=3)
                if not hmac.compare_digest(data.get('csrf',[''])[0],csrf):raise ValueError('Sessione scaduta')
                action=urlsplit(self.path).path
                if action not in ('/files/copy','/files/remove'):raise ValueError('Operazione sconosciuta')
                value=data.get('path' if action.endswith('copy') else 'job',[''])[0]
                if not jobs.acquire(blocking=False):raise ValueError('Operazione già in corso: aggiorna lo stato')
                last['message']='Operazione in corso: aggiorna lo stato.'
                def work():
                    try:
                        if action.endswith('copy'):archive.copy(value);last['message']='Copia verificata su Drive. Puoi liberare spazio.'
                        else:archive.remove_verified(value);last['message']='Originale rimosso. Copia Drive conservata.'
                    except Exception as error:last['message']=str(error)
                    finally:jobs.release()
                threading.Thread(target=work,daemon=True).start();self.page()
            except Exception as error:self.page(str(error),409)
    server=ThreadingHTTPServer(('127.0.0.1',port),Handler)
    port=server.server_address[1];origin=f'http://127.0.0.1:{port}'
    return server


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',default=str(Path.home()/'.config/dan-files/config.json'));parser.add_argument('--init',action='store_true');parser.add_argument('--port',type=int,default=8766);args=parser.parse_args()
    path=Path(args.config).expanduser()
    if args.init:
        if path.exists():raise SystemExit('Configurazione già esistente: non sovrascritta')
        root=input('Cartella da gestire (es. ~/storage/shared/Download): ').strip();device=input('Nome dispositivo (es. tablet): ').strip();remote=input('Remote rclone Google Drive già configurato (es. drive:): ').strip()
        result=subprocess.run(['rclone','config','dump'],capture_output=True,check=True)
        if json.loads(result.stdout).get(remote.rstrip(':'),{}).get('type')!='drive':raise SystemExit('Serve un remote rclone Google Drive configurato')
        config={'root':root,'device':device,'remote':remote,'state':str(path.parent/'state')};Archive(config)
        path.parent.mkdir(parents=True,exist_ok=True,mode=0o700);fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(fd,'w') as out:json.dump(config,out)
        print('Configurato. Avvia senza --init; non sono stati copiati o cancellati file.');return
    make_server(Archive(json.loads(path.read_text())),args.port).serve_forever()
if __name__=='__main__':main()
