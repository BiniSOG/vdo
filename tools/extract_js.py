"""Extracts the browser's injected scripts from BrowserScreen.kt into .js files for checking."""
import re
import sys

src, out_dir = sys.argv[1], sys.argv[2]
s = open(src).read()
for name in ["SNIFF_JS", "HOOK_JS"]:
    head = 'private const val %s = """' % name
    a = s.index(head) + len(head)
    b = s.index('"""', a)
    js = s[a:b]
    if re.search(r"\$[A-Za-z_{]", js):
        print(name, "contains a Kotlin string template")
    open("%s/%s.js" % (out_dir, name), "w").write(js)
    print("wrote", name)
