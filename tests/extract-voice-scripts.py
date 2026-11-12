"""Extract the shipped JavaScript from the Android source for executable checks."""
import json, re
from pathlib import Path
source = Path('app/src/main/java/com/dimitriazzarone/chatgptmessenger/MainActivity.java').read_text()
literal = re.compile(r'"(?:[^"\\]|\\.)*"')
def strings(part):
    return ''.join(json.loads(m.group()) for m in literal.finditer(part))
page_method = source.split('    private String danPageScript() {',1)[1].split('    private boolean sameVoicePage',1)[0]
page = strings(page_method.split('return ',1)[1])
observer = source.split('    private void injectAssistantObserver() {',1)[1].split('    @Override',1)[0]
expr = observer.split('String script =',1)[1].split('webView.evaluateJavascript',1)[0]
a,b = expr.split('+ danPageScript()',1)
Path('/tmp/dan146-observer.js').write_text(strings(a)+page+strings(b))
