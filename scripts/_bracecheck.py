import re, sys
f = sys.argv[1]
s = open(f, encoding='utf-8').read()
# strip block comments
s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
# strip line comments
s = re.sub(r'//[^\n]*', '', s)
# strip string literals: triple-quoted, then double-quoted (Kotlin has no single-char literal braces issues here)
s = re.sub(r'"""[\s\S]*?"""', '', s)
s = re.sub(r'"(\\.|[^"\\])*"', '""', s)
op = s.count('{'); cl = s.count('}')
print("file=%s open=%d close=%d delta=%d" % (f.split('/')[-1], op, cl, op-cl))
unb = [(i, ln.strip()) for i, ln in enumerate(s.splitlines(), 1) if ln.count('{') != ln.count('}')]
print("unbalanced lines:", len(unb))
for u in unb[:15]:
    print(u)
