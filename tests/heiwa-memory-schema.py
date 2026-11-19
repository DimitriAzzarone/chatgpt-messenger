"""Exercise the actual SQLite schema/triggers used by the Android index."""
import pathlib,re,sqlite3
source=pathlib.Path('app/src/main/java/com/dimitriazzarone/chatgptmessenger/HeiwaMemoryIndex.java').read_text()
db=sqlite3.connect(':memory:')
for statement in re.findall(r'db\.execSQL\("(CREATE [^"\n]+)"\);',source):
    db.execute(statement)
assert db.execute("SELECT name FROM sqlite_master WHERE name='search'").fetchone()
def entry(ident,text):
    db.execute('INSERT OR IGNORE INTO entries VALUES(?,?,?,?,?)',(ident,'Profilo','user',text,123))
def link(origin,ident):
    db.execute('INSERT OR IGNORE INTO links VALUES(?,?)',(origin,ident))
entry('same','Sono nato a Zürich');link('A','same')
entry('same','Sono nato a Zürich');link('B','same')
assert db.execute('SELECT count(*) FROM entries').fetchone()[0]==1
assert db.execute("SELECT count(*) FROM search WHERE search MATCH 'zurich*'").fetchone()[0]==1
db.commit()
# Replacing A preserves the same record still referenced by B.
db.execute('DELETE FROM links WHERE source=?',('A',));entry('new','Mi piace leggere');link('A','new')
db.execute('DELETE FROM entries WHERE id NOT IN (SELECT entry FROM links)')
assert db.execute('SELECT count(*) FROM entries').fetchone()[0]==2
db.commit()
# A failed import rolls back both data and full-text triggers.
db.execute("INSERT INTO entries VALUES('bad','Bad','user','corrupted',0)");db.rollback()
assert db.execute("SELECT count(*) FROM search WHERE search MATCH 'corrupted'").fetchone()[0]==0
# Dropping B's last reference also removes its searchable text.
db.execute("DELETE FROM links WHERE source='B'")
db.execute('DELETE FROM entries WHERE id NOT IN (SELECT entry FROM links)')
assert db.execute("SELECT count(*) FROM search WHERE search MATCH 'zurich*'").fetchone()[0]==0
assert db.execute('SELECT count(*) FROM entries').fetchone()[0]==1
print('SQLite memory: dedup across files, Unicode FTS, replacement, rollback and orphan cleanup passed')
